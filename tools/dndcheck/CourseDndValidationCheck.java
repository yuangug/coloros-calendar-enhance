package com.lcodexs.coursealarm;

import java.util.List;

/** Tests stale-schedule gating separately from session union behavior. */
public final class CourseDndValidationCheck {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static CourseDndSessions.Session session(String key, long start, long end, boolean automatic, boolean manual) {
        return new CourseDndSessions.Session(key, start, end, automatic, manual, false);
    }
    public static void main(String[] args) {
        String token = "0123456789abcdef".repeat(4);
        check(CourseDndValidation.validToken(token), "256-bit hex token is accepted");
        check(CourseDndValidation.validToken(token.toUpperCase()), "Hex case does not change entropy");
        check(!CourseDndValidation.validToken(null), "Missing callback identity cannot validate");
        check(!CourseDndValidation.validToken(""), "Empty callback identity cannot validate");
        check(!CourseDndValidation.validToken(token.substring(1)), "Truncated token is rejected");
        check(!CourseDndValidation.validToken("g" + token.substring(1)), "Non-hex token is rejected");

        CourseDndSessions schedule = new CourseDndSessions(List.of(
                session("a", 1000, 2000, true, false),
                session("b", 1500, 2500, true, false),
                session("c", 2500, 3000, true, false)));
        check(CourseDndValidation.activeLeaseEnd(schedule, 900) == 0, "Upcoming courses have no active lease");
        check(CourseDndValidation.nextBoundary(schedule, 900) == 1000, "First activation needs a start-boundary check");
        check(CourseDndValidation.activeLeaseEnd(schedule, 1100) == 2000, "Future overlapping course cannot extend the current lease");
        check(CourseDndValidation.nextBoundary(schedule, 1100) == 1500, "Overlapping start requires validation before its extension");
        check(CourseDndValidation.activeLeaseEnd(schedule, 1600) == 2500, "Fresh validation includes courses actually running");
        check(CourseDndValidation.nextBoundary(schedule, 1600) == 2500, "Adjoining future class is checked at its start");
        check(CourseDndValidation.activeLeaseEnd(schedule, 2500) == 3000, "Freshly validated adjoining occurrence receives its own lease");
        check(CourseDndValidation.nextBoundary(schedule, 3000) == 0, "Ended schedule has no timer");

        CourseDndSessions manual = new CourseDndSessions(List.of(session("manual", 1000, 2000, false, true)));
        check(CourseDndValidation.activeLeaseEnd(manual, 900) == 0, "A reservation cannot hold a DND lease before class");
        check(CourseDndValidation.nextBoundary(manual, 900) == 1000, "Manual reservation wakes and revalidates at actual start");
        check(CourseDndValidation.nextBoundary(new CourseDndSessions(List.of()), 900) == 0, "Empty schedule schedules no work");
        CourseDndSessions stopped = schedule.suppressActive(1600);
        check(CourseDndValidation.activeLeaseEnd(stopped, 1600) == 0, "Manual-off suppression never holds a lease");
        check(CourseDndValidation.nextBoundary(stopped, 1600) == 2500, "Suppression preserves only future distinct courses");

        check(!CourseDndValidation.mayRemainActive(true, false, 2000, 1100), "Pending validation cannot activate a stopped rule");
        check(CourseDndValidation.mayRemainActive(true, true, 2000, 1100), "Existing rule may finish its already validated lease");
        check(!CourseDndValidation.mayRemainActive(true, true, 2000, 2000), "Pending validation cannot delay the exact lease end");
        check(!CourseDndValidation.mayRemainActive(true, true, 0, 1100), "Clock/boot invalidation clears the lease");
        check(CourseDndValidation.mayRemainActive(false, false, 0, 1100), "Fresh validation permits a new activation");

        check(CourseDndValidation.nextRetry(true, true, 0, 0, 1000) == 1000, "First validation request is immediate");
        check(CourseDndValidation.nextRetry(true, true, 1, 1000, 2000) == 61000, "Repeated callbacks cannot flood validation broadcasts");
        check(CourseDndValidation.nextRetry(true, true, 2, 1000, 70000) == 70000, "Due retry can proceed after the interval");
        check(CourseDndValidation.nextRetry(true, true, 3, 1000, 70000) == 0, "Retries stop after three attempts");
        check(CourseDndValidation.nextRetry(true, false, 0, 0, 1000) == 0, "Missing token cannot schedule unauthenticated retries");
        check(CourseDndValidation.nextRetry(false, true, 0, 0, 1000) == 0, "Validated state has no retry timer");
        System.out.println("CourseDndValidation: " + checks + " checks passed");
    }
}
