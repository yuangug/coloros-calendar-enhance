package com.lcodexs.coursealarm;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

final class AlarmTime {
    record Plan(ZonedDateTime target, boolean useToday, boolean representable, long followUpMillis) {}

    private AlarmTime() {}

    static ZonedDateTime forCourse(Instant start, int offset, ZoneId zone) {
        ZonedDateTime course = start.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime midnight = course.toLocalDate().atStartOfDay(zone);
        ZonedDateTime alarm = course.minusMinutes(offset);
        return alarm.isBefore(midnight) ? midnight : alarm;
    }

    /** Clock's date-less one-shot API uses the next occurrence of the supplied time. */
    static Instant nextClockOccurrence(Instant now, LocalTime time, ZoneId zone) {
        var date = now.atZone(zone).toLocalDate();
        var today = date.atTime(time).atZone(zone).withLaterOffsetAtOverlap().toInstant();
        return today.isAfter(now) ? today
                : date.plusDays(1).atTime(time).atZone(zone).withLaterOffsetAtOverlap().toInstant();
    }

    static Plan plan(Instant now, ZonedDateTime today, ZonedDateTime tomorrow, Instant nextTick) {
        boolean useToday = today != null && today.toInstant().isAfter(now);
        ZonedDateTime target = useToday ? today : tomorrow;
        boolean representable = target != null
                && nextClockOccurrence(now, target.toLocalTime(), target.getZone()).equals(target.toInstant());
        Instant followUp = null;
        if (tomorrow != null) {
            if (useToday && !nextTick.isBefore(tomorrow.toInstant())) {
                followUp = today.plusMinutes(1).toInstant();
            } else if (!useToday && !representable) {
                followUp = tomorrow.minusDays(1).plusMinutes(1).toInstant();
            }
        }
        long followUpMillis = followUp != null && followUp.isAfter(now) && followUp.isBefore(nextTick)
                ? followUp.toEpochMilli() : 0;
        return new Plan(target, useToday, representable, followUpMillis);
    }
}
