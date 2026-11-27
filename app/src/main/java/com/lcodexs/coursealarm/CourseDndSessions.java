package com.lcodexs.coursealarm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure occurrence state. No course content, system sound settings, or Android dependencies. */
final class CourseDndSessions {
    static final int MAX_SESSIONS = 2048;

    static final class Session {
        final String key;
        final long start, end;
        final boolean automatic, manual, suppressed;

        Session(String key, long start, long end, boolean automatic, boolean manual, boolean suppressed) {
            if (key == null || key.isEmpty() || key.length() > 512 || key.chars().anyMatch(Character::isISOControl)
                    || start <= 0 || end <= start || end - start > 2L * 86400000L)
                throw new IllegalArgumentException("课程免打扰时间或身份无效");
            this.key = key; this.start = start; this.end = end;
            this.automatic = automatic; this.manual = manual; this.suppressed = suppressed;
        }

        boolean scheduled() { return !suppressed && (manual || automatic); }
        boolean reserved(long now) { return now < start && scheduled(); }
        boolean active(long now) {
            return start <= now && now < end && scheduled();
        }

        boolean sameTime(Session other) { return other != null && start == other.start && end == other.end; }
    }

    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();

    CourseDndSessions(Collection<Session> initial) {
        if (initial.size() > MAX_SESSIONS) throw new IllegalArgumentException("课程免打扰实例过多");
        for (Session session : initial)
            if (sessions.put(session.key, session) != null) throw new IllegalArgumentException("课程实例重复");
    }

    List<Session> all() { return new ArrayList<>(sessions.values()); }
    Session get(String key) { return sessions.get(key); }

    CourseDndSessions replace(Collection<Session> incoming, long now) {
        CourseDndSessions validated = new CourseDndSessions(incoming);
        List<Session> merged = new ArrayList<>();
        for (Session next : validated.sessions.values()) {
            if (next.end <= now) continue;
            Session previous = sessions.get(next.key);
            boolean preserve = next.sameTime(previous);
            merged.add(new Session(next.key, next.start, next.end, next.automatic,
                    preserve && previous.manual, preserve && previous.suppressed));
        }
        return new CourseDndSessions(merged);
    }

    CourseDndSessions toggle(String key, long start, long end, long now) {
        Session previous = get(key);
        return toggle(key, start, end, now, previous != null && (previous.active(now) || previous.reserved(now)));
    }

    CourseDndSessions toggle(String key, long start, long end, long now, boolean currentlyActive) {
        Session previous = get(key);
        if (previous == null || previous.start != start || previous.end != end || end <= now)
            throw new IllegalStateException("本次课程已结束或已变化，请刷新课程提醒");
        LinkedHashMap<String, Session> next = new LinkedHashMap<>(sessions);
        boolean off = currentlyActive;
        next.put(key, new Session(key, start, end, previous.automatic, !off, off));
        return new CourseDndSessions(next.values());
    }

    CourseDndSessions suppressActive(long now) {
        List<Session> next = new ArrayList<>();
        for (Session previous : sessions.values()) {
            boolean active = previous.active(now);
            next.add(active ? new Session(previous.key, previous.start, previous.end, previous.automatic, false, true) : previous);
        }
        return new CourseDndSessions(next);
    }

    /** Permission withdrawal cancels bookings too; regranting access is not a new booking. */
    CourseDndSessions revoke(long now) {
        List<Session> next = new ArrayList<>();
        for (Session session : sessions.values())
            next.add(session.active(now) || (session.manual && session.reserved(now))
                    ? new Session(session.key, session.start, session.end, session.automatic, false, true) : session);
        return new CourseDndSessions(next);
    }

    CourseDndSessions prune(long now) {
        List<Session> next = new ArrayList<>();
        for (Session session : sessions.values()) if (session.end > now) next.add(session);
        return new CourseDndSessions(next);
    }

    boolean active(long now) {
        for (Session session : sessions.values()) if (session.active(now)) return true;
        return false;
    }

    long nextBoundary(long now) {
        long next = Long.MAX_VALUE, activeUntil = 0;
        for (Session session : sessions.values()) {
            if (session.active(now)) activeUntil = Math.max(activeUntil, session.end);
            else if (session.reserved(now))
                next = Math.min(next, session.start);
        }
        if (activeUntil != 0) {
            // Overlaps and adjoining classes need one rule boundary, not a wake for each class.
            // This also avoids consuming the platform's idle-alarm quota on redundant refreshes.
            boolean extended;
            do {
                extended = false;
                for (Session session : sessions.values()) {
                    if (session.scheduled() && session.start <= activeUntil
                            && session.end > activeUntil) {
                        activeUntil = session.end; extended = true;
                    }
                }
            } while (extended);
            return activeUntil;
        }
        return next == Long.MAX_VALUE ? 0 : next;
    }
}
