package com.lcodexs.coursealarm;

/** Pure validation/lease bounds. A future scheduled course never extends an unvalidated lease. */
final class CourseDndValidation {
    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_MILLIS = 60000L;

    private CourseDndValidation() { }

    static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{64}");
    }

    static long activeLeaseEnd(CourseDndSessions sessions, long now) {
        long end = 0;
        for (CourseDndSessions.Session session : sessions.all())
            if (session.active(now)) end = Math.max(end, session.end);
        return end;
    }

    /** Recheck before another automatic course can begin, even inside an existing DND union. */
    static long nextBoundary(CourseDndSessions sessions, long now) {
        long next = activeLeaseEnd(sessions, now);
        for (CourseDndSessions.Session session : sessions.all()) {
            if (!session.reserved(now)) continue;
            if (next == 0 || session.start < next) next = session.start;
        }
        return next;
    }

    static boolean mayRemainActive(boolean pending, boolean alreadyActive, long leaseUntil, long now) {
        return !pending || (alreadyActive && now < leaseUntil);
    }

    static long nextRetry(boolean pending, boolean hasToken, int attempts, long lastAttempt, long now) {
        if (!pending || !hasToken || attempts >= MAX_ATTEMPTS) return 0;
        if (attempts <= 0) return now;
        return Math.max(now, lastAttempt + RETRY_MILLIS);
    }
}
