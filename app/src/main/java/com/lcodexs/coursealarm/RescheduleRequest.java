package com.lcodexs.coursealarm;

import java.util.*;

/** One explicit reschedule command; all week numbers are one-based and day/node indices zero-based. */
final class RescheduleRequest {
    enum Scope { CURRENT_WEEK, FUTURE_WEEKS, CUSTOM_WEEKS, DAY }
    final DragSession session;
    final Scope scope;
    final int targetDay, targetStart, targetEnd, sourceWeek, sourceDay, targetWeek;
    final BitSet targetWeeks;
    final Set<Long> eventIds;
    private RescheduleRequest(DragSession session, Scope scope, int day, int start, int end,
                              BitSet weeks, int sourceWeek, int sourceDay, int targetWeek, Collection<Long> ids) {
        this.session = session; this.scope = scope; this.targetDay = day; this.targetStart = start;
        this.targetEnd = end; this.targetWeeks = weeks == null ? new BitSet() : (BitSet) weeks.clone();
        this.sourceWeek = sourceWeek; this.sourceDay = sourceDay; this.targetWeek = targetWeek;
        this.eventIds = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }
    static RescheduleRequest range(DragSession session, Scope scope, int day, int start, int end, BitSet weeks) {
        if (scope == Scope.DAY) throw new IllegalArgumentException("按天调课需要来源和目标日期");
        return new RescheduleRequest(session, scope, day, start, end, weeks,
                session.displayedWeek, session.source.course.day, session.displayedWeek, Collections.singleton(session.eventId));
    }
    static RescheduleRequest day(DragSession session, int sourceWeek, int sourceDay,
                                 int targetWeek, int targetDay, Collection<Long> ids) {
        return new RescheduleRequest(session, Scope.DAY, targetDay, -1, -1, null,
                sourceWeek, sourceDay, targetWeek, ids);
    }
}
