package com.lcodexs.coursealarm;

enum AlarmPeriod {
    MORNING("早晨闹钟", "上午", "课程闹钟 ", "", ""),
    AFTERNOON("下午闹钟", "下午", "课程下午闹钟 ", "afternoon_", "_afternoon");

    final String title;
    final String session;
    final String labelPrefix;
    final String prefPrefix;
    final String uiSuffix;

    AlarmPeriod(String title, String session, String labelPrefix, String prefPrefix, String uiSuffix) {
        this.title = title;
        this.session = session;
        this.labelPrefix = labelPrefix;
        this.prefPrefix = prefPrefix;
        this.uiSuffix = uiSuffix;
    }

    boolean ownsLabel(String label) {
        return label != null && label.startsWith(labelPrefix);
    }

    /** The timetable's session boundaries take priority over wall-clock noon. */
    static AlarmPeriod at(int minute, int[] starts, int[] ends, int morningCount, int afternoonCount) {
        if (morningCount < 0) return minute < 720 ? MORNING : null;
        if (morningCount > 0 && minute >= starts[0] && minute < ends[morningCount - 1]) return MORNING;
        if (afternoonCount > 0 && minute >= starts[morningCount]
                && minute < ends[morningCount + afternoonCount - 1]) return AFTERNOON;
        return null;
    }
}
