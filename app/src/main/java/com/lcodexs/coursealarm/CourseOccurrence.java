package com.lcodexs.coursealarm;

/** One real occurrence, independent of the week currently displayed by the timetable. */
final class CourseOccurrence {
    enum Phase { UPCOMING, RUNNING, ENDED, EXPIRED, DISMISSED }
    final String backend, tableIdentity, key, title, room, teacher;
    final long calendarId, eventId, start, end, reminderAt;

    CourseOccurrence(String backend, String tableIdentity, long calendarId, long eventId,
                     long start, long end, long reminderAt, String title, String room, String teacher) {
        if (backend == null || tableIdentity == null || calendarId <= 0 || eventId <= 0
                || start <= 0 || end <= start || end - start > 2L * 86400000L)
            throw new IllegalArgumentException("无效的课程实例");
        this.backend = backend; this.tableIdentity = tableIdentity;
        this.calendarId = calendarId; this.eventId = eventId;
        this.start = start; this.end = end; this.reminderAt = reminderAt;
        this.title = title == null ? "" : title; this.room = room == null ? "" : room;
        this.teacher = teacher == null ? "" : teacher;
        key = backend + ":" + tableIdentity + ":" + eventId + ":" + start;
    }

    long expiresAt(int retainMinutes) { return end + retainMinutes * 60000L; }

    Phase phase(long now, int retainMinutes, boolean dismissed) {
        if (dismissed) return Phase.DISMISSED;
        if (now >= expiresAt(retainMinutes)) return Phase.EXPIRED;
        if (now >= end) return Phase.ENDED;
        return now >= start ? Phase.RUNNING : Phase.UPCOMING;
    }

    int progress(long now) { return progress(start, end, now); }

    static int progress(long start, long end, long now) {
        if (now <= start) return 0;
        if (now >= end) return 10000;
        return (int) ((now - start) * 10000L / (end - start));
    }

    String timing(long now) { return timing(start, end, now); }

    static String timing(long start, long end, long now) {
        if (now >= end) return "课程已结束";
        if (now >= start) return "上课中 · 还剩 " + minutesUntil(end, now) + " 分钟";
        return "距上课 " + minutesUntil(start, now) + " 分钟";
    }

    static long minutesUntil(long target, long now) { return target <= now ? 0 : (target - now - 1) / 60000L + 1; }

    /** Next phase, remaining-minute label, or visible progress update, bounded by expiry. */
    static long nextIslandUpdate(long start, long end, long expires, long last, boolean visibleProgress) {
        if (last >= end) return expires;
        long phaseEnd = last < start ? start : end;
        long next = Math.min(expires, last + Math.floorMod(phaseEnd - last - 1, 60000L) + 1);
        return visibleProgress && last >= start ? Math.min(next, last + 5000L) : next;
    }
}
