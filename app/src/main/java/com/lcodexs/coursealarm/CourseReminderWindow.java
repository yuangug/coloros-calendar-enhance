package com.lcodexs.coursealarm;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Wall time describes a lesson; elapsed time orders asynchronous native START requests. */
final class CourseReminderWindow {
    static final long DELIVERY_GRACE = 15_000L;
    private static final long MAX_RETRY = 300_000L;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private static final class Attempt {
        long nextCheck, sendingUntil, delay = DELIVERY_GRACE;
    }

    // In the host snapshot a disabled reminder has reminderAt == start.
    static boolean configured(CourseOccurrence item) {
        return item != null && item.reminderAt > 0 && item.reminderAt < item.start;
    }

    static boolean active(CourseOccurrence item, long now) {
        return configured(item) && now >= item.reminderAt && now < item.end;
    }

    static boolean keep(CourseOccurrence item, long now, boolean enabled, boolean dismissed) {
        return enabled && !dismissed && active(item, now);
    }

    static long nextBoundary(CourseOccurrence item, long now) {
        if (!configured(item) || now >= item.end) return Long.MAX_VALUE;
        return now < item.reminderAt ? item.reminderAt : item.end;
    }

    synchronized long nextAttempt(String key) {
        Attempt a = attempts.get(key);
        return a == null ? 0 : Math.max(a.nextCheck, a.sendingUntil);
    }

    /** Back off unsupported hosts without blocking an actual incoming Calendar reminder. */
    synchronized boolean tryCheck(String key, long elapsed) {
        if (elapsed < nextAttempt(key)) return false;
        Attempt a = attempts.computeIfAbsent(key, ignored -> new Attempt());
        a.nextCheck = elapsed + a.delay;
        a.delay = Math.min(MAX_RETRY, 2 * a.delay);
        return true;
    }

    /** Native reminder and scheduled catch-up must compete for the same in-flight publication. */
    synchronized boolean trySend(String key, long elapsed) {
        Attempt a = attempts.computeIfAbsent(key, ignored -> new Attempt());
        if (elapsed < a.sendingUntil) return false;
        a.sendingUntil = elapsed + DELIVERY_GRACE;
        return true;
    }

    synchronized void retain(Set<String> keys) { attempts.keySet().retainAll(keys); }
}
