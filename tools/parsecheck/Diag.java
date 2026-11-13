package com.lcodexs.coursealarm;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性诊断：把「页面里应有多少课」与「解析器实际出了多少课」并排列出来。
 *
 * 用法：java -cp out com.lcodexs.coursealarm.Diag <课表页.html>
 *
 * 期望清单从页面自带的「课程明细表」里取（形如 <td>B00811007</td><td>形势与政策（三）</td>），
 * 所以不依赖任何外部信息 —— 明细表里存在、解析结果里没有的，就是被丢掉的课。
 */
public class Diag {
    public static void main(String[] args) throws Exception {
        String html = read(args[0]);
        System.out.println("HTML 长度=" + html.length());

        List<String> expected = expectedFromDetailTable(html);
        System.out.println("明细表里的课程数=" + expected.size());

        JwParser.ParseResult r = JwParser.parse(html);
        System.out.println("解析结果: " + r.log);

        Set<String> got = new LinkedHashSet<>();
        for (Course c : r.courses) got.add(norm(c.name));

        System.out.println("\n--- 解析出来的课 ---");
        for (Course c : r.courses) {
            System.out.println("  " + c.name + " · " + Course.dayName(c.day) + " 第" + (c.startNode + 1)
                    + "-" + (c.endNode + 1) + "节 · 周" + c.weeksText()
                    + " · " + c.teacher + " · " + c.room);
        }

        System.out.println("\n--- 明细表有、解析结果没有（缺失） ---");
        int missing = 0;
        for (String n : expected) {
            if (!got.contains(norm(n)) && !containsLoose(got, n)) {
                System.out.println("  MISSING  " + n);
                missing++;
            }
        }
        System.out.println("缺失合计=" + missing + " / " + expected.size());

        System.out.println("\n--- 解析有、明细表没有（多出来的） ---");
        for (String n : got) {
            if (!containsLoose(expected, n)) System.out.println("  EXTRA    " + n);
        }
    }

    /** 明细表：<td>课程代码</td><td>课程名</td> */
    private static List<String> expectedFromDetailTable(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)>\\s*([A-Z]\\d{8})\\s*</td>\\s*<td[^>]*>\\s*([^<]{2,40}?)\\s*</td>")
                .matcher(html);
        while (m.find()) {
            String name = m.group(2).replace("&nbsp;", " ").trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private static boolean containsLoose(Iterable<String> names, String target) {
        String t = norm(target);
        for (String n : names) {
            String a = norm(n);
            if (a.equals(t) || a.contains(t) || t.contains(a)) return true;
        }
        return false;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s　]", "").trim();
    }

    private static String read(String p) throws Exception {
        try (FileInputStream in = new FileInputStream(p)) {
            byte[] b = new byte[in.available()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }
}
