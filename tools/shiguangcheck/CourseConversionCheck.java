package com.lcodexs.coursealarm;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Synthetic import results only. No Android context or calendar provider. */
public final class CourseConversionCheck {
    private static final int[] START = {480, 535, 600, 710};
    private static final int[] END = {525, 580, 690, 810};
    private static int checks;
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static JSONObject course() throws Exception {
        return new JSONObject("{\"name\":\"离散数学\",\"day\":7,\"startSection\":1,\"endSection\":2,\"weeks\":[1,3,5]}");
    }
    private static Course parse(JSONObject row) throws Exception {
        return ShiguangCourses.parse(new JSONArray().put(row), 20, START, END).get(0);
    }
    private static void rejects(JSONArray rows) throws Exception {
        try { ShiguangCourses.parse(rows, 20, START, END); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid import accepted: " + rows);
    }
    public static void main(String[] args) throws Exception {
        Course c = parse(course());
        require(c.day == 6 && c.startNode == 0 && c.endNode == 1, "one-based weekday or period changed");
        require(c.weeksCount() == 3 && c.weeks.get(1) && c.weeks.get(3) && c.weeks.get(5), "odd weeks changed");
        require(c.teacher.isEmpty() && c.room.isEmpty(), "optional teacher or location required");
        c = parse(course().put("day", 1).put("teacher", "李老师").put("position", "实验楼 301"));
        require(c.day == 0 && "李老师".equals(c.teacher) && "实验楼 301".equals(c.room), "metadata lost");
        for (int[] example : new int[][]{{0, 0, 45}, {2, 2, 90}, {3, 3, 100}, {0, 1, 100}}) {
            JSONObject custom = course().put("isCustomTime", true).put("customStartTime", time(START[example[0]]))
                    .put("customEndTime", time(END[example[1]]));
            custom.remove("startSection"); custom.remove("endSection");
            c = parse(custom);
            require(c.startNode == example[0] && c.endNode == example[1]
                    && END[c.endNode] - START[c.startNode] == example[2], "exact custom clock changed");
        }
        for (JSONObject bad : new JSONObject[]{
                course().put("name", ""), course().put("name", JSONObject.NULL),
                course().put("day", 0), course().put("day", 8), course().put("day", 1.5),
                course().put("startSection", 0), course().put("endSection", 5), course().put("startSection", 3),
                course().put("weeks", new JSONArray()), course().put("weeks", new JSONArray("[0,1]")),
                course().put("weeks", new JSONArray("[21]")), course().put("weeks", new JSONArray("[1.5]")),
                course().put("isCustomTime", true).put("customStartTime", "08:01").put("customEndTime", "08:45"),
                course().put("isCustomTime", true).put("customStartTime", "25:00").put("customEndTime", "08:45")
        }) rejects(new JSONArray().put(course()).put(bad));
        rejects(new JSONArray());
        List<Course> sameName = ShiguangCourses.parse(new JSONArray().put(course()).put(course().put("day", 1)),
                20, START, END);
        require(sameName.size() == 2 && sameName.get(0).day != sameName.get(1).day, "same-name courses merged");
        if (args.length > 0) {
            ShiguangCatalog.setModulePath(args[0]);
            List<ShiguangCatalog.Adapter> catalog = ShiguangCatalog.load();
            require(catalog.size() == 299, "expected 298 upstream adapters plus XIT");
            require(ShiguangCatalog.bridge().contains("notifyTaskCompletion"), "bridge missing from APK");
            for (ShiguangCatalog.Adapter adapter : catalog) {
                if(adapter.localXit) require(adapter.url.equals("https://xgjwxt.xit.edu.cn/jsxsd/") &&
                        ShiguangCatalog.bundledScript("xit.js").contains("jxzl_query"), "local XIT adapter missing");
                else require(ShiguangCatalog.script(adapter).contains("saveImportedCourses"), "adapter script missing");
            }
        }
        System.out.println("PASS: " + checks + " course conversion and APK adapter checks");
    }
    private static String time(int m) { return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60); }
}
