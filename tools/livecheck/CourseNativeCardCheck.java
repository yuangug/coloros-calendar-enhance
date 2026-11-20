package com.lcodexs.coursealarm;

/** Isolated checks for the renderer's identity gate and local time progression. */
public final class CourseNativeCardCheck {
    private static int assertions;
    public static void main(String[] args) {
        String instance = "calendar-test-instance";
        eq(instance, CourseNativeCardContract.instanceFromKey("268452047|" + instance + "|1|1795085092596"));
        for (String invalid : new String[]{null, "", "course name", "268452048|abc|1|1795085092596",
                "268452047||1|1795085092596", "268452047|abc|1|0", "268452047|abc|-1|10",
                "268452047|abc|a|10", "268452047|abc|1|10|more", "268452047|abc|1",
                "268452047|abc|2147483648|10", "268452047|abc|1|9223372036854775808"})
            eq("", CourseNativeCardContract.instanceFromKey(invalid));
        long start = 1795085092596L;
        for (int minutes : new int[]{45, 90, 100, 1500}) {
            long end = start + minutes * 60000L;
            near(0, CourseNativeCardContract.fraction(start - 1, start, end));
            near(0, CourseNativeCardContract.fraction(start, start, end));
            near(.5f, CourseNativeCardContract.fraction(start + (end - start) / 2, start, end));
            near(1, CourseNativeCardContract.fraction(end, start, end));
            near(1, CourseNativeCardContract.fraction(end + 600000, start, end));
            float previous = 0;
            for (int minute = 1; minute <= minutes; minute++) {
                float value = CourseNativeCardContract.fraction(start + minute * 60000L, start, end);
                check(value > previous && value <= 1, "progress must advance locally through the whole interval");
                previous = value;
            }
        }
        // A two-section course with a break uses the complete wall-clock interval.
        near(.5f, CourseNativeCardContract.fraction(start + 50 * 60000L, start, start + 100 * 60000L));
        near(0, CourseNativeCardContract.fraction(start, start, start));
        near(0, CourseNativeCardContract.fraction(start, 0, start));
        near(0, CourseNativeCardContract.fraction(start, start + 1, start));
        System.out.println("Native card identity/time checks: " + assertions + " assertions passed");
    }
    private static void eq(String expected, String actual) { check(expected.equals(actual), "unexpected identity"); }
    private static void near(float expected, float actual) { check(Math.abs(expected - actual) < .00001f, "unexpected progress"); }
    private static void check(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
}
