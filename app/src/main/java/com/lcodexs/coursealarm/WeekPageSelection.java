package com.lcodexs.coursealarm;

/** Distinguish a user swipe/picker selection from the page we put on screen automatically. */
final class WeekPageSelection {
    private int requested = -1, observed = -1;
    boolean followsDefault() { return requested < 0; }
    void choose(int index) { requested = index; }
    void attached(int index) { observed = index; }
    void observed(int index) {
        if (index >= 0 && index != observed) { requested = index; observed = index; }
    }
    void entry(boolean advanced) { if (advanced) requested = -1; }
    int target(int defaultIndex, int count) {
        return Math.max(0, Math.min(count - 1, requested < 0 ? defaultIndex : requested));
    }
    int saved(boolean advanced) { return advanced ? -1 : requested; }
}
