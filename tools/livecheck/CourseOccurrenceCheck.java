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

    private static CourseOccurrence course(long start, long end) {
        return new CourseOccurrence("calendar", "stable-table-a", 11, 22, start, end,
                start - 30 * 60_000L, "Test course", "Room", "Teacher");
    }

    public static int run() {
        checks = 0;
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

    public static void main(String[] args) {
        System.out.println("CourseOccurrenceCheck PASS: " + run() + " assertions");
    }
}
