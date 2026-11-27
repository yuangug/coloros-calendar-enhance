package com.lcodexs.coursealarm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual recess intervals inside one occurrence; no inference from course name or duration. */
record CourseBreaks(List<Gap> gaps) {
    record Gap(long from, long until, int nextPeriod) { }
    static final CourseBreaks NONE = new CourseBreaks(Collections.emptyList());
    private static final int MAX_STAGE_GAPS = 14; // Both endpoint labels count towards the SDK's 16.

    CourseBreaks { gaps = Collections.unmodifiableList(new ArrayList<>(gaps)); }

    static CourseBreaks fromTable(long start, long end, ZoneId zone, int first, int last, int[] opens, int[] closes) {
        if (first < 0 || last <= first || opens == null || closes == null
                || last >= opens.length || last >= closes.length) return NONE;
        ZonedDateTime local = Instant.ofEpochMilli(start).atZone(zone);
        LocalDate date = local.toLocalDate();
        List<Gap> breaks = new ArrayList<>();
        long previousEnd = 0;
        for (int i = first; i <= last; i++) {
            if (opens[i] < 0 || opens[i] >= closes[i] || closes[i] > 1440) return NONE;
            long a = ZonedDateTime.ofLocal(date.atStartOfDay().plusMinutes(opens[i]), zone, local.getOffset()).toInstant().toEpochMilli();
            long b = ZonedDateTime.ofLocal(date.atStartOfDay().plusMinutes(closes[i]), zone, local.getOffset()).toInstant().toEpochMilli();
            // A moved exception can retain the old node numbers. Do not decorate it with another lesson's breaks.
            if (a >= b || i == first && Math.floorDiv(start, 60000) != Math.floorDiv(a, 60000)
                    || i == last && Math.floorDiv(end, 60000) != Math.floorDiv(b, 60000)) return NONE;
            if (i > first) {
                if (previousEnd > a) return NONE;
                if (previousEnd < a) breaks.add(new Gap(previousEnd, a, i + 1));
            }
            previousEnd = b;
        }
        return breaks.isEmpty() ? NONE : new CourseBreaks(breaks);
    }

    long[] encode() {
        long[] result = new long[3 * gaps.size()]; int i = 0;
        for (Gap gap : gaps) { result[i++] = gap.from; result[i++] = gap.until; result[i++] = gap.nextPeriod; }
        return result;
    }

    static CourseBreaks decode(long start, long end, long[] data) {
        if (data == null || data.length == 0 || data.length % 3 != 0 || data.length > 3 * 1440) return NONE;
        List<Gap> gaps = new ArrayList<>();
        long previous = start, period = 1;
        for (int i = 0; i < data.length; i += 3) {
            long a = data[i], b = data[i+1], p = data[i+2];
            if (a <= previous || b <= a || b >= end || p <= period || p > 1440) return NONE;
            gaps.add(new Gap(a, b, (int)p)); previous = b; period = p;
        }
        return new CourseBreaks(gaps);
    }

    boolean staged() { return !gaps.isEmpty() && gaps.size() <= MAX_STAGE_GAPS; }

    List<String> labels(String start, String end) {
        List<String> labels = new ArrayList<>();
        labels.add(start);
        for (Gap ignored : gaps) labels.add("课间");
        labels.add(end);
        return labels;
    }

    // The SDK spaces its dots evenly. Each teaching interval fills one segment;
    // the entire recess maps to the same dot, including both boundary instants.
    float fraction(long start, long end, long now) {
        if (!staged()) return CourseOccurrence.progress(start, end, now) / 10000f;
        if (now <= start) return 0f;
        if (now >= end) return 1f;
        int segments = gaps.size() + 1, index = 0;
        long left = start, right = end;
        for (Gap gap : gaps) {
            if (now < gap.from) { right = gap.from; break; }
            index++;
            if (now <= gap.until) return (float) index / segments;
            left = gap.until;
        }
        return (float)((index + (double)(now - left) / (right - left)) / segments);
    }

    String timing(long start, long end, long now) {
        for (Gap gap : gaps) {
            if (now < gap.from) break;
            if (now < gap.until) return "课间休息 · " + CourseOccurrence.minutesUntil(gap.until, now)
                    + " 分钟后上第 " + gap.nextPeriod + " 节";
        }
        return CourseOccurrence.timing(start, end, now);
    }

    long nextChange(long last) {
        for (Gap gap : gaps) {
            if (last < gap.from) return gap.from;
            if (last < gap.until) return last + Math.floorMod(gap.until - last - 1, 60000L) + 1;
        }
        return Long.MAX_VALUE;
    }
}
