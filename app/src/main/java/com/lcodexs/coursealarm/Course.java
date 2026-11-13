package com.lcodexs.coursealarm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** 与宿主 CourseEntity 对齐的中性课程模型（导出/导入都用它）。 */
final class Course {
    String name = "";
    String teacher = "";
    String room = "";
    int day;          // 0=周一 … 6=周日
    int startNode;    // 0=第1节
    int endNode;
    int color = 4;
    long dtstart;        // 首周该节开始时间（毫秒），从库里读来的原始值
    int durationSec;
    BitSet weeks = new BitSet();   // 1-based 教学周，set(1)=第1周

    int firstWeek() {
        int i = weeks.nextSetBit(1);
        return i < 0 ? 1 : i;
    }

    int lastWeek() {
        int i = weeks.length() - 1;
        return i < 1 ? firstWeek() : i;
    }

    int weeksCount() {
        int n = 0;
        for (int i = weeks.nextSetBit(1); i > 0; i = weeks.nextSetBit(i + 1)) n++;
        return n;
    }

    /** 连续周次压成区间，便于展示：4-16 */
    String weeksText() {
        List<String> parts = new ArrayList<>();
        int start = -1, prev = -1;
        for (int i = 1; i <= 60; i++) {
            boolean on = weeks.get(i);
            if (on) {
                if (start < 0) start = i;
                prev = i;
            } else if (start > 0) {
                parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
                start = -1;
            }
        }
        if (start > 0) parts.add(start == prev ? String.valueOf(start) : start + "-" + prev);
        return String.join(",", parts);
    }

    static String dayName(int day) {
        String[] n = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return day >= 0 && day < 7 ? n[day] : "?";
    }

    @Override
    public String toString() {
        return name + " " + dayName(day) + " 第" + (startNode + 1) + "-" + (endNode + 1)
                + "节 周" + weeksText() + " @" + room + " " + teacher;
    }
}
