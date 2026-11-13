package com.lcodexs.coursealarm;


import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 桌面回归：把「教务课表 HTML → 课程」与「导入文件格式判定」从 Android 里剥出来直接跑。
 *
 * 运行（JDK21，无需 Android SDK）：
 *   javac -encoding UTF-8 -d out JwParser.java Course.java ImportFormat.java ParseCheck.java
 *   java -cp out ParseCheck <fixtures 目录>
 *
 * 退出码 0 = 全部通过；1 = 有断言失败（CI 友好）。
 */
public class ParseCheck {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        System.out.println("== fixtures: " + root);

        // 1) 真实正方页（用户账号抓下来的 153 KB 页面）
        Path live = Paths.get("D:/lsp模块/.tmp/jw_live.html");
        if (Files.exists(live)) {
            JwParser.ParseResult r = parse(live);
            expect(r.courses.size() >= 22, "jw_live.html 门数 >= 22，实际 " + r.courses.size());
            expect(r.zhengfang >= 22, "jw_live.html 正方路径 >= 22，实际 " + r.zhengfang);
            System.out.println("   " + r.log);
        } else {
            System.out.println("   (skip jw_live.html: 不在 .tmp 里)");
        }

        // 2) 真实页面不得被"补课"污染（无脑合并会 19 → 45）
        if (Files.exists(live)) {
            JwParser.ParseResult r = parse(live);
            expect(r.courses.size() == 22, "jw_live.html 应为 22 门（19 是旧版按 kbcontent 切块、漏掉同格多课的结果），实际 " + r.courses.size());
        }

        // 2b) 同一格里堆多门课（正方用 ------ 分隔）+ 备注行里的"无节次课程"
        Path multi = root.resolve("multi-course-cell.html");
        if (Files.exists(multi)) {
            JwParser.ParseResult mr = parse(multi);
            System.out.println("   multi-course-cell: " + mr.log);
            expect(mr.courses.size() == 3, "同格三门课应解析出 3 门，实际 " + mr.courses.size());
            expect(has(mr.courses, "形势与政策（三）", 2, 0), "形势与政策（三）周三 第1节");
            expect(has(mr.courses, "国家安全教育", 2, 0), "国家安全教育周三 第1节");
            expect(has(mr.courses, "创新创业基础", 2, 0), "创新创业基础周三 第1节");
            expect(mr.unscheduled.size() >= 1 && mr.unscheduled.get(0).contains("劳动教育"),
                    "备注行里的「劳动教育 1-20周」进 unscheduled，实际 " + mr.unscheduled);
        }
        // 2c) 强智/URP 风格：表头「周一」、行头「1-2节」、格内课程名/教师/周次/地点 换行堆叠
        Path qz = root.resolve("qz-qiangzhi.html");
        if (Files.exists(qz)) {
            JwParser.ParseResult qr = parse(qz);
            System.out.println("   qz-qiangzhi: " + qr.log);
            expect(qr.courses.size() == 5, "qz-qiangzhi 应为 5 门，实际 " + qr.courses.size());
            expect(has(qr.courses, "形势与政策（三）", 3, 0), "形势与政策（三）周四 第1节");
            expect(has(qr.courses, "高等数学", 0, 0), "高等数学 周一 第1节");
            expect(weeksOf(qr.courses, "体育").equals("1,3,5,7,9,11,13,15"), "体育 1-16周(单) → 单周");
        }

        // 2d) 新版正方 jwglxt 的 JSON 课表
        Path js = root.resolve("jwglxt-kbList.json");
        if (Files.exists(js)) {
            String txt = read(js);
            JwParser.ParseResult jr = JwParser.parse(txt);
            System.out.println("   jwglxt-json: " + jr.log);
            expect(jr.courses.size() == 3, "JSON 课表应为 3 门，实际 " + jr.courses.size());
            expect(has(jr.courses, "形势与政策（三）", 2, 0), "JSON：形势与政策（三）周三 第1节");
            expect(ImportFormat.detect(txt.getBytes(StandardCharsets.UTF_8), "kb.json", null) == ImportFormat.JSON,
                    "kbList JSON → ImportFormat.JSON");
        }
        // 3) 变异页：单引号 class + 裸文本课名 + rowspan 行头 + 数字行头 + colspan
        Path variant = root.resolve("variant-zhijiao.html");
        JwParser.ParseResult v = parse(variant);
        System.out.println("   variant-zhijiao: " + v.log);
        expect(v.courses.size() == 6, "variant-zhijiao 应为 6 门，实际 " + v.courses.size());
        expect(has(v.courses, "高等数学A", 0, 0), "高等数学A 周一 第1节（rowspan 行头 1-2）");
        expect(has(v.courses, "线性代数", 1, 0), "线性代数 周二 第1节（第 2 行沿用 rowspan 行头）");
        expect(has(v.courses, "大学物理", 2, 0), "大学物理 周三 第1节");
        expect(has(v.courses, "体育", 0, 2) && has(v.courses, "体育", 1, 2),
                "体育 colspan=2 → 周一/周二 第3节各一门");
        expect(has(v.courses, "形势与政策", 4, 2), "形势与政策 周五 第3节（数字行头 3）");
        // 3) 格式判定（bug1 的核心：不看名字也不会把 xlsx 认成 ics）
        byte[] xlsx = Files.readAllBytes(Paths.get("D:/lsp模块/CourseAlarm/app/src/main/assets/xlsx_template.xlsx"));
        expect(ImportFormat.detect(xlsx, "whatever.txt", null) == ImportFormat.XLSX, "xlsx 内容 → XLSX");
        expect(ImportFormat.detect(null, "课程表_20261113.xlsx  (37 KB)", null) == ImportFormat.XLSX,
                "带大小尾巴的名字 → XLSX（修复前 label.endsWith 会失手）");
        expect(ImportFormat.detect(null, "课程表_20261113.ics  (14 KB)", null) == ImportFormat.ICS,
                "带大小尾巴的 .ics 名字 → ICS");
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";
        expect(ImportFormat.detect(ics.getBytes(StandardCharsets.UTF_8), "blob.bin", null) == ImportFormat.ICS,
                "BEGIN:VCALENDAR 内容（名字是 .bin）→ ICS");
        expect(ImportFormat.detect(xlsx, "course.xlsx", "application/octet-stream") == ImportFormat.XLSX,
                "MIME 与内容冲突时以内容为准");
        expect(ImportFormat.detect(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}, "a.xls", null)
                == ImportFormat.XLS_OLE, "老式 .xls（OLE）单独识别，给明确提示");
        expect(ImportFormat.detect("hello".getBytes(StandardCharsets.UTF_8), "a.txt", null) == ImportFormat.UNKNOWN,
                "未知内容 → UNKNOWN");

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static JwParser.ParseResult parse(Path p) throws Exception {
        return JwParser.parse(read(p));
    }

    private static String read(Path p) throws Exception {
        try (FileInputStream in = new FileInputStream(p.toFile())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String weeksOf(List<Course> list, String name) {
        for (Course c : list) if (c.name.contains(name)) return c.weeksText();
        return "";
    }

    private static boolean has(List<Course> list, String name, int day, int startNode) {
        for (Course c : list) {
            if (c.name.contains(name) && c.day == day && c.startNode == startNode) return true;
        }
        return false;
    }

    private static void expect(boolean ok, String what) {
        if (ok) {
            System.out.println("PASS  " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }
}
