package com.lcodexs.coursealarm;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Pure timeline regression tests. Does not load Android, start an app, or read calendar data. */
public final class CourseOccurrenceCheck {
    private static int checks;
    private static final long START = 1_795_046_400_000L;

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void rejects(Runnable action, String description) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(description);
    }

    private static void testReminderWindow() {
        CourseOccurrence item = course(START, START + 100 * 60000L);
        for (int lead : new int[]{5, 15, 30, 60}) {
            long remind = START - lead * 60000L;
            CourseOccurrence lesson = new CourseOccurrence("local", "table", 17, 55, START, item.end, remind, "Math", "", "");
            check(CourseReminderWindow.nextBoundary(lesson, remind - 1) == remind, "Wake at the configured reminder");
            check(!CourseReminderWindow.active(lesson, remind - 1) && CourseReminderWindow.active(lesson, remind), "Inclusive reminder boundary");
            check(CourseReminderWindow.active(lesson, START + 11 * 60000L), "Does not stop ten minutes into class");
            check(CourseReminderWindow.active(lesson, START + 50 * 60000L), "Includes break");
            check(CourseReminderWindow.active(lesson, item.end - 1) && !CourseReminderWindow.active(lesson, item.end), "Exclusive class end");
            check(CourseReminderWindow.nextBoundary(lesson, START) == item.end &&
                    CourseReminderWindow.nextBoundary(lesson, item.end) == Long.MAX_VALUE, "No new START during retention");
        }
        CourseOccurrence off = new CourseOccurrence("local", "table", 17, 55, START, item.end, START, "Math", "", "");
        check(!CourseReminderWindow.active(off, START) && CourseReminderWindow.nextBoundary(off, START) == Long.MAX_VALUE,
                "Disabled native reminder cannot create an activity at the start");
        long halfway = START + 50 * 60000L;
        check(CourseReminderWindow.keep(item, halfway, true, false), "A cold start inside class can catch up");
        check(!CourseReminderWindow.keep(item, halfway, true, true), "Do not restore a manually dismissed class");
        check(!CourseReminderWindow.keep(item, halfway, false, false), "Window does not run without opt-in");
        CourseOccurrence following = course(item.end, item.end + 45 * 60000L);
        check(CourseReminderWindow.active(item, item.end - 1) && CourseReminderWindow.active(following, item.end - 1),
                "A following reminder can begin before this class ends");
        check(!CourseReminderWindow.active(item, item.end) && CourseReminderWindow.active(following, item.end),
                "Ending the first of two adjacent classes cannot end the second");
        CourseReminderWindow gate = new CourseReminderWindow();
        check(gate.tryCheck("a", 1000) && !gate.tryCheck("a", 1001), "Unsupported host backoff");
        check(gate.trySend("a", 1010) && !gate.trySend("a", 1010), "Incoming and scheduled send share a single START");
        check(!gate.tryCheck("a", 16000) && gate.tryCheck("a", 16010), "Wait for asynchronous native delivery");
        check(gate.trySend("b", 1100) && !gate.trySend("b", 1101), "Overlapping courses have separate publication slots");
        check(gate.trySend("a", 16010), "Retry unconfirmed send");
        gate.retain(java.util.Set.of("a"));
        check(gate.nextAttempt("b") == 0 && gate.nextAttempt("a") > 16010, "Withdrawing one window preserves the other");
    }

    private static CourseOccurrence course(long start, long end) {
        return new CourseOccurrence("calendar", "stable-table-a", 11, 22, start, end,
                start - 30 * 60_000L, "Test course", "Room", "Teacher");
    }

    public static int run() {
        checks = 0;
        checkBreakStages();
        testReminderWindow();
        testAstraWindow();
        for (int minutes : new int[]{45, 90, 100}) {
            long length = minutes * 60_000L;
            CourseOccurrence item = course(START, START + length);
            check(item.progress(START - 1) == 0, "Progress is zero before " + minutes + " minute class");
            check(item.progress(START) == 0, "Progress starts at zero");
            check(item.progress(START + length / 4) == 2500, "One quarter of the actual duration");
            check(item.progress(START + length / 2) == 5000, "Half of the actual duration");
            check(item.progress(START + length * 3 / 4) == 7500, "Three quarters of the actual duration");
            check(item.progress(START + length - 1) < 10000, "Last millisecond is not prematurely complete");
            check(item.progress(START + length) == 10000, "End is exactly full");
            check(item.progress(START + length + 60_000L) == 10000, "Retention stays full");
            check(item.phase(START - 1, 5, false) == CourseOccurrence.Phase.UPCOMING, "Pre-class phase");
            check(item.phase(START, 5, false) == CourseOccurrence.Phase.RUNNING, "Start boundary");
            check(item.phase(START + length - 1, 5, false) == CourseOccurrence.Phase.RUNNING, "End boundary remains exclusive");
            check(item.phase(START + length, 5, false) == CourseOccurrence.Phase.ENDED, "End starts retained finished state");
            check(item.phase(START + length + 299_999L, 5, false) == CourseOccurrence.Phase.ENDED, "Retained up to five minutes");
            check(item.phase(START + length + 300_000L, 5, false) == CourseOccurrence.Phase.EXPIRED, "Expires at five minutes without resetting");
            check(item.phase(START + length, 0, false) == CourseOccurrence.Phase.EXPIRED, "Immediate removal has no extra tick");
            check(item.timing(START + length).equals("课程已结束"), "Ended wording");
        }

        CourseOccurrence twoPeriods = course(START, START + 100 * 60_000L);
        check(twoPeriods.progress(START + 45 * 60_000L) == 4500, "45 + 10 break + 45 uses the complete 100-minute range");
        check(twoPeriods.progress(START + 50 * 60_000L) == 5000, "Progress continues through the break");
        check(twoPeriods.progress(START + 55 * 60_000L) == 5500, "Second period does not reset progress");
        check(twoPeriods.timing(START + 99 * 60_000L).contains("还剩 1 分钟"), "Minute label at exact final minute");
        check(twoPeriods.timing(START + 99 * 60_000L + 59_999L).contains("还剩 1 分钟"), "Round remaining time up before end");
        check(twoPeriods.timing(START - 1).equals("距上课 1 分钟"), "Last pre-class millisecond is not zero minutes");
        check(twoPeriods.timing(START - 60_001L).equals("距上课 2 分钟"), "Pre-class minutes round up");
        long end = twoPeriods.end, expires = twoPeriods.expiresAt(5);
        check(CourseOccurrence.nextIslandUpdate(START, end, expires, START - 1000, true) == START,
                "Enter the running phase at the start deadline");
        check(CourseOccurrence.nextIslandUpdate(START, end, expires, end - 65_000, false) == end - 60_000,
                "The collapsed capsule changes wording at the remaining-minute boundary");
        check(CourseOccurrence.nextIslandUpdate(START, end, expires, START + 1000, true) == START + 6000,
                "Update visible progress before the next minute boundary");
        check(CourseOccurrence.nextIslandUpdate(START, end, expires, end - 500, true) == end,
                "Five-second progress throttling must not delay the ended phase");
        check(CourseOccurrence.nextIslandUpdate(START, end, expires, end, true) == expires,
                "After class, no further progress occurs before the fixed retention deadline");
        check(CourseOccurrence.nextIslandUpdate(START, end, end, end - 500, true) == end,
                "Immediate dismissal does not create a later refresh deadline");
        check(CourseAstraStyle.fromId(null) == CourseAstraStyle.TIMETABLE
                && CourseAstraStyle.fromId("unknown") == CourseAstraStyle.TIMETABLE, "Default and invalid capsule style");
        CourseAstraStyle number = CourseAstraStyle.fromId("remaining");
        check(number.glyph(START, end, START - 1) == CourseAstraStyle.Glyph.MINUTES
                && number.glyph(START, end, end - 1) == CourseAstraStyle.Glyph.MINUTES
                && number.glyph(START, end, end) == CourseAstraStyle.Glyph.CHECK,
                "Remaining minutes stay visible until the course ends and never show zero");
        CourseAstraStyle phase = CourseAstraStyle.fromId("phase");
        check(phase.glyph(START, end, START - 1) == CourseAstraStyle.Glyph.TIMETABLE
                && phase.glyph(START, end, START) == CourseAstraStyle.Glyph.BOOK
                && phase.glyph(START, end, end) == CourseAstraStyle.Glyph.CHECK,
                "Phase icons change exactly at the start and end of class");
        check(CourseAstraStyle.TIMETABLE.glyph(START, end, end) == CourseAstraStyle.Glyph.TIMETABLE,
                "Fixed timetable icon does not change during retention");
        check(CourseOccurrence.minutesUntil(START, START - 1) == 1
                && CourseOccurrence.minutesUntil(end, START) == 100
                && CourseOccurrence.minutesUntil(end, end) == 0,
                "Capsule remaining minutes use real deadlines and round up");
        int previous = -1;
        for (long now = START - 30_000L; now <= twoPeriods.end + 30_000L; now += 10_000L) {
            int progress = twoPeriods.progress(now);
            check(progress >= previous && progress >= 0 && progress <= 10000, "Progress is bounded and monotonic");
            previous = progress;
        }

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        long overnightStart = ZonedDateTime.of(2026, 11, 19, 23, 30, 0, 0, shanghai).toInstant().toEpochMilli();
        long overnightEnd = ZonedDateTime.of(2026, 11, 20, 1, 0, 0, 0, shanghai).toInstant().toEpochMilli();
        long midnight = ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli();
        CourseOccurrence overnight = course(overnightStart, overnightEnd);
        check(overnight.progress(midnight) == 3333, "A cross-midnight course does not reset at the date boundary");
        check(overnight.phase(midnight, 5, false) == CourseOccurrence.Phase.RUNNING, "After midnight remains running");
        check(overnight.timing(midnight).equals("上课中 · 还剩 60 分钟"), "Cross-midnight remaining time");

        ZoneId newYork = ZoneId.of("America/New_York");
        long dstStart = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, newYork).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
        long dstEnd = ZonedDateTime.of(2026, 11, 1, 2, 30, 0, 0, newYork).toInstant().toEpochMilli();
        CourseOccurrence dst = course(dstStart, dstEnd);
        check(dstEnd - dstStart == 120 * 60_000L, "DST fixture covers two real hours");
        check(dst.progress(dstStart + 60 * 60_000L) == 5000, "Use elapsed timestamps through a daylight-saving transition");

        for (long now : new long[]{START - 1, START, twoPeriods.end, twoPeriods.end + 600_000L})
            check(twoPeriods.phase(now, 5, true) == CourseOccurrence.Phase.DISMISSED,
                    "A dismissed instance must not become active or reappear at a later boundary");

        CourseOccurrence renamed = new CourseOccurrence("calendar", "stable-table-a", 11, 22,
                START, twoPeriods.end, START, "Renamed", "Another room", "Another teacher");
        check(twoPeriods.key.equals(renamed.key), "Changing presentation content does not create another instance identity");
        check(!twoPeriods.key.equals(new CourseOccurrence("other-backend", "stable-table-a", 11, 22,
                START, twoPeriods.end, START, "", "", "").key), "Backend isolates instances");
        check(!twoPeriods.key.equals(new CourseOccurrence("calendar", "reused-table-id", 11, 22,
                START, twoPeriods.end, START, "", "", "").key), "Stable table identity isolates reused numerical IDs");
        check(!twoPeriods.key.equals(new CourseOccurrence("calendar", "stable-table-a", 11, 23,
                START, twoPeriods.end, START, "Test course", "Room", "Teacher").key), "Same-title distinct events stay distinct");
        check(!twoPeriods.key.equals(course(START + 7 * 86_400_000L, twoPeriods.end + 7 * 86_400_000L).key),
                "Different teaching weeks have different occurrence identities");
        CourseOccurrence emptyText = new CourseOccurrence("calendar", "stable-table-a", 11, 22,
                START, twoPeriods.end, START, null, null, null);
        check(emptyText.title.isEmpty() && emptyText.room.isEmpty() && emptyText.teacher.isEmpty(), "Missing optional metadata is safe");

        rejects(() -> course(START, START), "Reject zero-length classes");
        rejects(() -> course(START, START - 1), "Reject backwards classes");
        rejects(() -> course(0, 60_000L), "Reject absent start timestamps");
        rejects(() -> course(START, START + 2 * 86_400_000L + 1), "Reject implausible durations");
        rejects(() -> new CourseOccurrence(null, "table", 11, 22, START, START + 1000, START, "", "", ""), "Require backend identity");
        rejects(() -> new CourseOccurrence("calendar", null, 11, 22, START, START + 1000, START, "", "", ""), "Require table identity");
        rejects(() -> new CourseOccurrence("calendar", "table", 0, 22, START, START + 1000, START, "", "", ""), "Require actual calendar ID");
        rejects(() -> new CourseOccurrence("calendar", "table", 11, 0, START, START + 1000, START, "", "", ""), "Require actual event ID");
        return checks;
    }

    private static void testAstraWindow() {
        CourseOccurrence item = course(START, START + 90 * 60000L);
        check(!CourseIslandWindow.eligible(item, item.reminderAt - 1, 5, false, false), "No premature capsule");
        check(CourseIslandWindow.eligible(item, item.reminderAt, 5, false, false), "Reminder does not wait for shade");
        for (int retention : new int[]{0, 1, 5, 10}) {
            long expires = item.expiresAt(retention);
            for (long now : new long[]{START, item.end - 1, item.end, expires - 1, expires}) {
                check(CourseIslandWindow.eligible(item, now, retention, false, false) == (now < expires),
                        "Losing the native receipt does not change the lesson deadline");
                check(!CourseIslandWindow.eligible(item, now, retention, true, true), "Dismissed capsule stays closed");
            }
        }
        CourseOccurrence off = new CourseOccurrence("local", "table", 17, 55, START, item.end, START, "Math", "", "");
        check(!CourseIslandWindow.eligible(off, START, 5, false, false), "No simulated reminder when disabled");
        var delivery = new CourseIslandWindow.Delivery();
        long t = 100;
        for (int failure = 0; failure < 10; failure++) {
            check(delivery.due("before", t), "Slow SDK connection must continue retrying past four attempts");
            delivery.failed(t, false);
            check(!delivery.due("before", t) && delivery.next() > t && delivery.next() <= t + 60_000,
                    "Repeated refreshes obey bounded backoff");
            t = delivery.next();
        }
        delivery.accepted(t);
        check(!delivery.due("before", t + 1) && delivery.due("running", t + 1), "Start wakes the same capsule");
        delivery.accepted(t + 1);
        check(delivery.due("running", t + 120_001), "A killed broker is contacted again with unchanged content");
        delivery.failed(t + 120_001, true);
        check(delivery.due("ended", t + 120_002), "Ending a course is never delayed by a retry timer");
    }

    private static void checkBreakStages() {
        long m = 60000;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long start = java.time.LocalDate.of(2026,11,25).atTime(8,0).atZone(zone).toInstant().toEpochMilli();
        int[] opens={400,480,520,600,660}, closes={450,510,555,660,705};
        long end=start+225*m;
        CourseBreaks breaks=CourseBreaks.fromTable(start,end,zone,1,4,opens,closes);
        check(breaks.labels("08:00","11:45").equals(java.util.List.of("08:00","课间","课间","11:45"))
                && breaks.gaps().size()==2,"Only real gaps produce interior dots");
        check(breaks.equals(CourseBreaks.decode(start,end,breaks.encode())),"IPC preserves complete break schedule and equality");
        check(breaks.fraction(start,end,start)==0 && breaks.fraction(start,end,end)==1,"Staged endpoints");
        check(Math.abs(breaks.fraction(start,end,start+35*m)-1f/3)<0.00001f,"First unequal period reaches first recess dot");
        check(Math.abs(breaks.fraction(start,end,start+97*m+30000)-2f/3)<0.00001f,"Longer break reaches second dot");
        float previous=-1;
        for(long t=start;t<=end;t+=m) {
            float progress=breaks.fraction(start,end,t);
            check(progress>previous,"Fill never stops or reverses, including during a break"); previous=progress;
        }
        check(breaks.timing(start,end,start+30*m).equals("课间休息 · 10 分钟后上第 3 节"),"Recess begins at previous period end");
        check(breaks.timing(start,end,start+40*m).startsWith("上课中"),"Next period starts at exact boundary");
        check(breaks.nextChange(start+25*m)==start+30*m && breaks.nextChange(start+39*m+123)==start+40*m,"Wake at both ends of a break");
        check(!CourseBreaks.fromTable(start,end,zone,1,1,opens,closes).staged(),"No stages on single lessons");
        check(!CourseBreaks.fromTable(start+m,end,zone,1,4,opens,closes).staged(),"Stale node numbers are not a timetable");
        check(!CourseBreaks.fromTable(start,end-m,zone,1,4,opens,closes).staged(),"Event ending at a different time is not staged");
        check(!CourseBreaks.fromTable(start,end,zone,1,8,opens,closes).staged(),"Ignore out-of-range nodes");
        check(CourseBreaks.decode(start,end,new long[]{start+4*m,end+1,3})==CourseBreaks.NONE
                && CourseBreaks.decode(start,end,null)==CourseBreaks.NONE,"Malformed or older payload keeps ordinary progress");
        int[] a=new int[16], b=new int[16];
        for(int i=0;i<a.length;i++) {a[i]=480+2*i;b[i]=a[i]+1;}
        check(CourseBreaks.fromTable(start,start+29*m,zone,0,14,a,b).labels("a","b").size()==16,"Maximum SDK stages");
        CourseBreaks tooMany=CourseBreaks.fromTable(start,start+31*m,zone,0,15,a,b);
        check(!tooMany.staged() && tooMany.timing(start,start+31*m,start+m).startsWith("课间休息"),"Beyond SDK cap, preserve actual break text");
    }

    public static void main(String[] args) {
        System.out.println("CourseOccurrenceCheck PASS: " + run() + " assertions");
    }
}
