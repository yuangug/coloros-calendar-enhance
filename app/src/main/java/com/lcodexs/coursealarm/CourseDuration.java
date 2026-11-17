package com.lcodexs.coursealarm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ColorOS legacy seconds and the fixed-duration subset of ISO 8601. */
final class CourseDuration {
    private static final Pattern LEGACY = Pattern.compile("P([0-9]+)S");
    private static final Pattern ISO = Pattern.compile("P(?:([0-9]+)D)?(?:T(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+)S)?)?");

    static int parseSeconds(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            Matcher legacy = LEGACY.matcher(text);
            if (legacy.matches()) return Math.toIntExact(Long.parseLong(legacy.group(1)));
            Matcher iso = ISO.matcher(text);
            if (!iso.matches() || text.equals("P") || text.endsWith("T")) return 0;
            long total = 0;
            long[] factors = {86400, 3600, 60, 1};
            for (int i = 1; i <= 4; i++) {
                if (iso.group(i) != null) total = Math.addExact(total,
                        Math.multiplyExact(Long.parseLong(iso.group(i)), factors[i - 1]));
            }
            return Math.toIntExact(total);
        } catch (ArithmeticException | NumberFormatException invalid) {
            return 0;
        }
    }
}
