package com.lcodexs.coursealarm;

/** Astra owns the capsule for the lesson's wall-clock window, independently of shade subscriptions. */
final class CourseIslandWindow {
    static boolean eligible(CourseOccurrence item, long now, int retention, boolean receipt, boolean dismissed) {
        return !dismissed && now < item.expiresAt(retention)
                && (receipt || CourseReminderWindow.configured(item) && now >= item.reminderAt);
    }

    static final class Delivery {
        private String content;
        private long due, backoff;

        boolean due(String value, long elapsed) {
            if (!value.equals(content)) { content = value; due = 0; backoff = 1500; }
            return elapsed >= due;
        }
        long next() { return due; }
        void accepted(long elapsed) { due = elapsed + 120_000L; backoff = 1500; }
        void failed(long elapsed, boolean unavailable) {
            due = elapsed + (unavailable ? 120_000L : backoff);
            backoff = Math.min(60_000L, backoff * 2);
        }
    }
}
