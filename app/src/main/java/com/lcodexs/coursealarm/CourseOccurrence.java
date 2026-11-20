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

    int progress(long now) {
        if (now <= start) return 0;
        if (now >= end) return 10000;
        return (int) ((now - start) * 10000L / (end - start));
    }

    String timing(long now) {
        if (now >= end) return "课程已结束";
        if (now >= start) return "上课中 · 还剩 " + ((end - now + 59999L) / 60000L) + " 分钟";
        return "距上课 " + ((start - now + 59999L) / 60000L) + " 分钟";
    }
}
