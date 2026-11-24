package com.lcodexs.coursealarm;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.BitSet;

/** The device clock is never changed. Exercise clock boundaries and page intent separately. */
public final class WeekFinishCheck {
    static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    static final long NONE = WeekFinishSchedule.NONE;
    static int checks;

    public static void main(String[] args) {
        WeekFinishSchedule term = new WeekFinishSchedule(time(CN, "2026-11-02T00:00"), 7, CN);
        // This week is week 4. A grey Friday entry belongs to other weeks only.
        course(term, 0, "08:10", 100, 1, 4);
        course(term, 2, "14:00", 90, 4);
        course(term, 4, "16:30", 45, 3, 5);
        long end = time(CN, "2026-11-25T15:30");
        var tuesday = term.at(time(CN, "2026-11-24T16:00"), true, NONE);
        test(tuesday.realIndex() == 3 && !tuesday.offer(3) && tuesday.nextCheck() == end, "still has a Wednesday class");
        test(!term.at(end - 1, true, NONE).finished(), "last course in progress");
        var done = term.at(end, true, NONE);
        test(done.offer(3) && !done.offer(2) && !done.offer(4), "only offer on the displayed real week");
        test(done.defaultIndex() == 3, "completion alone does not navigate");
        long receipt = done.weekKey();
        var clicked = term.at(end, true, receipt);
        test(clicked.advanced() && clicked.defaultIndex() == 4 && !clicked.offer(3), "click once, go exactly one week");
        test(term.at(time(CN, "2026-11-29T23:59:59.999"), true, receipt).defaultIndex() == 4, "Sunday re-entry");
        var monday = term.at(time(CN, "2026-11-30T00:00"), true, receipt);
        test(monday.defaultIndex() == 4 && !monday.advanced() && !monday.finished(), "Monday expires; no double advance");
        test(!term.at(end, false, receipt).finished() && term.at(end, false, receipt).defaultIndex() == 3, "turning off restores real week");
        course(term, 6, "09:10", 45, 4);
        test(!term.at(end, true, receipt).advanced() && !term.at(end, true, receipt).finished(), "incoming reschedule revokes redirect");

        WeekFinishSchedule empty = new WeekFinishSchedule(time(CN, "2026-11-04T15:00"), 2, CN);
        test(!empty.at(time(CN, "2026-11-03T20:00"), true, NONE).finished(), "before midweek semester start");
        var free = empty.at(time(CN, "2026-11-04T00:00"), true, NONE);
        test(free.offer(0) && free.nextCheck() == time(CN, "2026-11-09T00:00"), "empty week with non-Monday start");
        test(!empty.at(time(CN, "2026-11-09T00:00"), true, free.weekKey()).finished(), "last term week has no destination");
        test(!empty.at(time(CN, "2026-11-17T08:00"), true, NONE).finished(), "semester ended");

        WeekFinishSchedule overnight = new WeekFinishSchedule(time(CN, "2026-12-28T00:00"), 4, CN);
        course(overnight, 6, "23:30", 90, 1);
        long yearEnd = time(CN, "2027-01-04T01:00");
        test(!overnight.at(yearEnd-1, true, NONE).finished() && overnight.at(yearEnd, true, NONE).offer(1), "Sunday course still running next week");
        test(overnight.at(time(CN, "2027-01-03T22:00"), true, NONE).nextCheck() == time(CN, "2027-01-04T00:00"), "week boundary before course end");
        WeekFinishSchedule excluded = new WeekFinishSchedule(time(CN, "2026-11-02T00:00"), 5, CN);
        excluded.addCourse(weeks(1,2), 4, LocalTime.of(9,0), CN, 0, 45*60_000L,
                "20261106T010000Z,20261113", "20261114T100000");
        test(excluded.at(time(CN,"2026-11-02T09:00"),true,NONE).offer(0), "cancelled occurrence");
        test(!excluded.at(time(CN,"2026-11-13T11:00"),true,NONE).finished(), "additional Saturday occurrence");
        test(excluded.at(time(CN,"2026-11-14T10:45"),true,NONE).offer(1), "rescheduled occurrence ended");

        ZoneId ny = ZoneId.of("America/New_York");
        long second0130 = java.time.OffsetDateTime.parse("2026-11-01T01:30:00-05:00").toInstant().toEpochMilli();
        WeekFinishSchedule repeated = new WeekFinishSchedule(time(ny,"2026-10-26T00:00"),3,ny);
        repeated.addCourse(weeks(1),6,LocalTime.of(1,30),ny,second0130,45*60_000L,null,null);
        test(!repeated.at(second0130+44*60_000L,true,NONE).finished(), "second 01:30 course is not missed");
        WeekFinishSchedule skipRepeated = new WeekFinishSchedule(time(ny,"2026-10-26T00:00"),3,ny);
        skipRepeated.addCourse(weeks(1),6,LocalTime.of(1,30),ny,second0130,45*60_000L,"20261101",null);
        test(skipRepeated.at(second0130,true,NONE).offer(0), "date-only exclusion covers repeated hour");
        for (String[] dates : new String[][]{{"2026-03-02","2026-03-09"},{"2026-10-26","2026-11-02"}}) {
            WeekFinishSchedule dst = new WeekFinishSchedule(time(ny, dates[0]+"T00:00"), 3, ny);
            long boundary = time(ny, dates[1]+"T00:00");
            var sunday = dst.at(boundary - 1, true, NONE);
            test(sunday.realIndex() == 0 && sunday.nextCheck() == boundary, "DST Sunday boundary "+dates[0]);
            test(dst.at(boundary, true, sunday.weekKey()).realIndex() == 1
                    && !dst.at(boundary, true, sunday.weekKey()).advanced(), "DST Monday "+dates[1]);
        }

        WeekPageSelection page = new WeekPageSelection();
        page.attached(4); page.observed(4);
        test(page.followsDefault() && page.saved(true) == -1, "observing auto position is not a manual selection");
        page.observed(1);
        test(page.target(4,7) == 1, "manual swipe survives redraw/data rebind");
        page.entry(true);
        test(page.target(4,7) == 4, "re-entry restores just-finished week's destination");
        page.choose(3); page.attached(3); page.observed(3);
        test(page.target(4,7) == 3 && page.saved(true) == -1, "explicit return to this week; process restart follows default");
        page.choose(2); page.entry(false);
        test(page.target(3,7) == 2 && page.saved(false) == 2, "ordinary browsing preserved without a receipt");
        page.choose(-1);
        test(page.target(3,7) == 3, "disabled redirect returns following page to real week");
        System.out.println("Week completion: " + checks + " checks passed");
    }
    static void course(WeekFinishSchedule table, int day, String clock, int minutes, int... w) {
        table.addCourse(weeks(w), day, LocalTime.parse(clock), table.zone, 0, minutes*60_000L, null, null);
    }
    static BitSet weeks(int... numbers) {
        BitSet result = new BitSet();
        for (int w : numbers) result.set(w-1);
        return result;
    }
    static long time(ZoneId zone, String local) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }
    static void test(boolean valid, String label) { if (!valid) throw new AssertionError(label); checks++; }
}
