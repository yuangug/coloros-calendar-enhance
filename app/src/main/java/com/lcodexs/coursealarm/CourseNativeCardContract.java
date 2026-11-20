package com.lcodexs.coursealarm;

/** Pure validation/math shared by the SystemUI decorator and isolation checks. */
final class CourseNativeCardContract {
    private CourseNativeCardContract() { }

    static String instanceFromKey(String key) {
        if (key == null || key.length() > 1024) return "";
        // SystemUIPlugin Seedling origin v: service|instance|entrance|timestamp.
        String[] parts = key.split("\\|", -1);
        if (parts.length != 4 || !"268452047".equals(parts[0]) || parts[1].isEmpty()) return "";
        try {
            if (Integer.parseInt(parts[2]) < 0 || Long.parseLong(parts[3]) <= 0) return "";
            return parts[1];
        } catch (NumberFormatException ignored) { return ""; }
    }

    static float fraction(long now, long start, long end) {
        if (start <= 0 || end <= start || now <= start) return 0f;
        if (now >= end) return 1f;
        return (float) (((double) now - start) / ((double) end - start));
    }
}
