package com.lcodexs.coursealarm;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Map an adapter result to the exact timetable used by the import confirmation. */
final class ShiguangCourses {
    static List<Course> parse(JSONArray rows, int weeks, int[] starts, int[] ends) throws Exception {
        if (rows.length() == 0 || rows.length() > 2000) throw new IllegalArgumentException("拾光未返回课程或课程数量超出限制");
        List<Course> courses = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            Course c = new Course();
            try {
                JSONObject row = rows.getJSONObject(i);
                if (!(row.opt("name") instanceof String)) throw new IllegalArgumentException("缺少课程名称");
                c.name = row.getString("name").trim();
                if (c.name.isEmpty()) throw new IllegalArgumentException("课程名为空");
                c.teacher = row.isNull("teacher") ? "" : row.getString("teacher");
                c.room = row.isNull("position") ? "" : row.getString("position");
                c.day = integer(row.get("day"), 1, 7, "星期") - 1;
                if (row.optBoolean("isCustomTime", false)) {
                    c.startNode = exact(starts, row.getString("customStartTime"));
                    c.endNode = exact(ends, row.getString("customEndTime"));
                } else {
                    c.startNode = integer(row.get("startSection"), 1, starts.length, "开始节次") - 1;
                    c.endNode = integer(row.get("endSection"), 1, ends.length, "结束节次") - 1;
                }
                if (c.startNode > c.endNode) throw new IllegalArgumentException("结束节次早于开始节次");
                JSONArray selected = row.getJSONArray("weeks");
                if (selected.length() == 0) throw new IllegalArgumentException("缺少上课周次");
                for (int j = 0; j < selected.length(); j++) c.weeks.set(integer(selected.get(j), 1, weeks, "周次"));
            } catch (Exception e) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条课程「" + c.name + "」：" + e.getMessage());
            }
            courses.add(c);
        }
        return courses;
    }
    private static int integer(Object value, int min, int max, String field) {
        try {
            int n = Integer.parseInt(value.toString());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(field + "须为 " + min + " 至 " + max + " 的整数");
    }
    private static int exact(int[] times, String clock) {
        if (clock.matches("(?:[01]?[0-9]|2[0-3]):[0-5][0-9]")) {
            String[] parts = clock.split(":");
            int minutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            for (int i = 0; i < times.length; i++) if (times[i] == minutes) return i;
        }
        throw new IllegalArgumentException("自定义时间 " + clock + " 与目标课表节次不匹配");
    }
}
