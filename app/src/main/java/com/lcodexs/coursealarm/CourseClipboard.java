package com.lcodexs.coursealarm;

import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable course content; never carries an event ID into a paste. */
final class CourseClipboard {
    private CourseClipboard() { }

    static String snapshot(ClassLoader host, Object course) throws Exception {
        if (course == null) throw new IllegalArgumentException("没有可复制的课程");
        Class<?> type = Class.forName("com.google.gson.Gson", false, host);
        Object gson = type.getConstructor().newInstance();
        return (String) type.getMethod("toJson", Object.class).invoke(gson, course);
    }

    static String at(String source, int day, int start) throws Exception {
        JSONObject copy = new JSONObject(source);
        int length = copy.getInt("endNode") - copy.getInt("startNode");
        if (length < 0 || start < 0 || day < 0 || day > 6)
            throw new IllegalArgumentException("课程位置无效");
        copy.remove("event");
        copy.put("dayOfWeek", day).put("startNode", start).put("endNode", Math.addExact(start, length));
        copy.put("createTime", System.currentTimeMillis()).put("startTime", 0).put("endTime", 0)
                .put("timeDuration", "");
        return copy.toString();
    }

    static Course read(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        Course course = new Course();
        course.name = value.getString("courseName");
        course.teacher = value.optString("teacher", "");
        course.room = value.optString("room", "");
        course.color = value.getInt("color");
        course.day = value.getInt("dayOfWeek");
        course.startNode = value.getInt("startNode");
        course.endNode = value.getInt("endNode");
        JSONArray weeks = value.getJSONArray("week");
        for (int i = 0; i < weeks.length(); i++) {
            if (!(weeks.get(i) instanceof Boolean)) throw new IllegalArgumentException("课程周次无效");
            if (weeks.getBoolean(i)) course.weeks.set(i + 1);
        }
        return course;
    }

    static boolean overlaps(Course first, Course second) {
        return first.day == second.day && first.startNode <= second.endNode
                && first.endNode >= second.startNode && first.weeks.intersects(second.weeks);
    }
}
