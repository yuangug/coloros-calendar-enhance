package com.lcodexs.coursealarm;

/** Stable values shared by the settings page and the module's Star Island publisher. */
enum CourseAstraStyle {
    TIMETABLE("timetable", "课表格"),
    REMAINING("remaining", "剩余分钟"),
    PHASE("phase", "阶段图标");

    enum Glyph { TIMETABLE, MINUTES, BOOK, CHECK }

    final String id;
    final String label;

    CourseAstraStyle(String id, String label) {
        this.id = id;
        this.label = label;
    }

    static CourseAstraStyle fromId(String id) {
        for (CourseAstraStyle style : values()) if (style.id.equals(id)) return style;
        return TIMETABLE;
    }

    Glyph glyph(long start, long end, long now) {
        return switch (this) {
            case TIMETABLE -> Glyph.TIMETABLE;
            case REMAINING -> now < end ? Glyph.MINUTES : Glyph.CHECK;
            case PHASE -> now < start ? Glyph.TIMETABLE : now < end ? Glyph.BOOK : Glyph.CHECK;
        };
    }
}
