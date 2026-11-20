package com.lcodexs.coursealarm;

import java.util.List;

/** Standalone regression checks; javac with CourseDndSessions.java, no Android runtime needed. */
public final class CourseDndSessionsCheck {
    private static int checks;
    private static CourseDndSessions.Session session(String key, long start, long end, boolean automatic) {
        return new CourseDndSessions.Session(key, start, end, automatic, false, false);
    }
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void rejects(Runnable action, String description) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(description);
    }
    public static void main(String[] args) {
        CourseDndSessions empty = new CourseDndSessions(List.of());
        CourseDndSessions manual = new CourseDndSessions(List.of(session("a", 1000, 2000, false)));
        check(!manual.active(900), "Manual-only schedule is initially inactive");
        check(manual.nextBoundary(900) == 0, "No alarm for a disabled automatic schedule");
        manual = manual.toggle("a", 1000, 2000, 900);
        check(manual.active(900), "Manual request starts immediately before the class");
        check(manual.nextBoundary(900) == 2000, "Manual request expires at class end");
        check(!manual.active(2000), "Class end is an exclusive bound");
        check(manual.prune(2000).all().isEmpty(), "Ended occurrence is pruned");

        CourseDndSessions automatic = new CourseDndSessions(List.of(session("a", 1000, 2000, true)));
        check(!automatic.active(999), "Automatic session waits for actual start");
        check(automatic.active(1000), "Automatic session starts at class start");
        check(automatic.nextBoundary(900) == 1000, "Next start is scheduled");
        check(automatic.toggle("a", 1000, 2000, 1100, false).get("a").manual,
                "An explicitly enabled disabled system rule is not mistaken for a stop request");
        check(automatic.toggle("a", 1000, 2000, 1100, false).active(1100),
                "An enable action stays enabled if automatic DND starts during permission grant");
        CourseDndSessions stopped = automatic.toggle("a", 1000, 2000, 1100);
        check(stopped.get("a").suppressed && !stopped.active(1100), "Manual off suppresses current automatic session");
        stopped = stopped.replace(List.of(session("a", 1000, 2000, true)), 1200);
        check(!stopped.active(1300) && stopped.nextBoundary(1300) == 0, "Refresh cannot reactivate a manually stopped occurrence");
        check(stopped.toggle("a", 1000, 2000, 1300, true).get("a").suppressed,
                "Repeated stop actions cannot reactivate the occurrence");
        CourseDndSessions restarted = stopped.toggle("a", 1000, 2000, 1300);
        check(restarted.active(1300) && !restarted.get("a").suppressed, "Explicit user request may re-enable the same occurrence");

        CourseDndSessions overlapping = new CourseDndSessions(List.of(session("a", 1000, 2000, true),
                session("b", 1500, 2500, true), session("c", 2500, 3000, true), session("d", 3200, 4000, true)));
        check(overlapping.nextBoundary(1100) == 3000, "Overlapping and adjoining occurrences share one end boundary");
        check(overlapping.nextBoundary(3000) == 3200, "A real gap releases DND and schedules the next start");
        CourseDndSessions oneStopped = overlapping.toggle("a", 1000, 2000, 1600);
        check(oneStopped.active(1600), "Stopping one course leaves another active course effective");
        check(oneStopped.nextBoundary(1600) == 3000, "Overlap expiry still uses the final active end");
        CourseDndSessions overridden = overlapping.suppressActive(1600);
        check(!overridden.active(1600), "System override suppresses all currently active occurrences");
        check(overridden.nextBoundary(1600) == 2500, "Future distinct occurrence remains eligible");

        CourseDndSessions changed = manual.replace(List.of(session("a", 1001, 2001, false)), 1100);
        check(!changed.active(1100), "Changed occurrence times cannot inherit manual activation");
        rejects(() -> changed.toggle("a", 1000, 2000, 1100), "Stale notification cannot toggle a rescheduled course");
        CourseDndSessions deleted = manual.replace(List.of(), 1100);
        check(!deleted.active(1100), "Deleting or switching table releases old occurrences");
        rejects(() -> deleted.toggle("a", 1000, 2000, 1100), "Deleted notification action is rejected");
        rejects(() -> automatic.toggle("a", 1000, 2000, 2000), "Expired action cannot restart DND");
        rejects(() -> automatic.replace(List.of(session("a", 1000, 2000, true), session("a", 1500, 2500, true)), 1100),
                "Duplicate incoming identities are rejected atomically");
        check(automatic.get("a").start == 1000 && automatic.active(1100), "Invalid replacement leaves original schedule untouched");
        rejects(() -> session("", 1000, 2000, true), "Empty occurrence identity is rejected");
        rejects(() -> session("a\n", 1000, 2000, true), "Control characters in identity are rejected");
        rejects(() -> session("a", 2000, 1000, true), "Reversed interval is rejected");
        rejects(() -> session("a", 1000, 1000 + 48L * 3600000L + 1, true), "Unbounded interval is rejected");
        CourseDndSessions overnight = new CourseDndSessions(List.of(session("night", 23L * 3600000L, 25L * 3600000L, true)));
        check(overnight.active(24L * 3600000L), "Cross-midnight class remains active");
        check(overnight.nextBoundary(24L * 3600000L) == 25L * 3600000L, "Cross-midnight class ends on the actual end date");
        check(empty.nextBoundary(1000) == 0, "Empty state has no background timer");
        System.out.println("CourseDndSessions: " + checks + " checks passed");
    }
}
