package com.lcodexs.coursealarm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/** Built from one table read. UI decisions need only the clock and two preferences. */
final class WeekFinishSchedule {
    static final long NONE = Long.MIN_VALUE;
    final ZoneId zone;
    private final LocalDate firstDay, firstMonday;
    private final long termStart;
    private final long[] mondays, lastEnd;

    WeekFinishSchedule(long termBegin, int count, ZoneId zone) {
        if (termBegin <= 0 || count < 1 || count > 104) throw new IllegalArgumentException("无效学期");
        this.zone = zone;
        firstDay = Instant.ofEpochMilli(termBegin).atZone(zone).toLocalDate();
        firstMonday = mondayOf(firstDay);
        termStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
        lastEnd = new long[count];
        mondays = new long[count + 1];
        for (int i = 0; i <= count; i++)
            mondays[i] = firstMonday.plusWeeks(i).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Host week flags are zero-based. Non-current grey cards have no occurrence in that week. */
    void addCourse(BitSet weeks, int weekday, LocalTime clock, ZoneId eventZone, long firstStart,
                   long durationMs, String exdates, String rdates) {
        if (weekday < 0 || weekday > 6 || durationMs <= 0) throw new IllegalArgumentException("无效课程时间");
        ZoneOffset originalOffset = firstStart > 0 ? eventZone.getRules().getOffset(Instant.ofEpochMilli(firstStart)) : null;
        Set<Long> excluded = dates(exdates, eventZone, clock, originalOffset);
        for (int i = weeks.nextSetBit(0); i >= 0 && i < lastEnd.length; i = weeks.nextSetBit(i + 1)) {
            LocalDate day = firstMonday.plusWeeks(i).plusDays(weekday);
            if (day.isBefore(firstDay)) continue;
            // A stored first occurrence may use the second copy of a repeated local hour.
            long start = ZonedDateTime.ofLocal(day.atTime(clock), eventZone, originalOffset).toInstant().toEpochMilli();
            if (start >= firstStart && !excluded.contains(start)) include(start, durationMs);
        }
        for (long start : dates(rdates, eventZone, clock, originalOffset))
            if (!excluded.contains(start)) include(start, durationMs);
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    static Set<Long> dates(String raw, ZoneId zone, LocalTime clock, ZoneOffset preferred) {
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptySet();
        Set<Long> result = new HashSet<>();
        // CalendarProvider also accepts a timezone identifier followed by a semicolon.
        int semi = raw.indexOf(';');
        if (semi >= 0) { zone = ZoneId.of(raw.substring(0, semi)); raw = raw.substring(semi + 1); }
        for (String token : raw.split(",", -1)) {
            String value = token.trim();
            if (value.length() == 8) {
                LocalDateTime local = LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atTime(clock);
                result.add(ZonedDateTime.ofLocal(local, zone, preferred).toInstant().toEpochMilli());
            } else {
                boolean utc = value.endsWith("Z");
                LocalDateTime local = LocalDateTime.parse(utc ? value.substring(0, value.length()-1) : value, STAMP);
                result.add(ZonedDateTime.ofLocal(local, utc ? ZoneOffset.UTC : zone, preferred).toInstant().toEpochMilli());
            }
        }
        return result;
    }

    private int index(long time) {
        int found = Arrays.binarySearch(mondays, time);
        return found >= 0 ? found : -found - 2;
    }

    private void include(long start, long duration) {
        long end = Math.addExact(start, duration);
        // A Sunday course can still be in progress on Monday. Intervals are [start, end).
        for (int i = Math.max(0, index(start)); i < lastEnd.length && mondays[i] < end; i++)
            lastEnd[i] = Math.max(lastEnd[i], end);
    }

    record Decision(int realIndex, long weekKey, boolean finished, boolean advanced, long nextCheck) {
        int defaultIndex() { return realIndex + (advanced ? 1 : 0); }
        boolean offer(int displayed) { return finished && !advanced && displayed == realIndex; }
    }

    Decision at(long now, boolean enabled, long markedWeek) {
        int i = index(now);
        if (now < termStart || i < 0) return new Decision(0, NONE, false, false, termStart);
        if (i >= lastEnd.length) return new Decision(lastEnd.length - 1, NONE, false, false, NONE);
        long key = firstMonday.toEpochDay() + i * 7L;
        boolean available = enabled && i + 1 < lastEnd.length;
        boolean finished = available && now >= lastEnd[i];
        long next = available && lastEnd[i] > now ? Math.min(lastEnd[i], mondays[i+1]) : mondays[i+1];
        return new Decision(i, key, finished, finished && markedWeek == key, next);
    }
}
