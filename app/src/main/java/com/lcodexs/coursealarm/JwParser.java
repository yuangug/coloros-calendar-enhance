package com.lcodexs.coursealarm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务课表 HTML 解析器（纯 java.util，无 Android 依赖 —— 可在桌面 JVM 上直接回归）。
 *
 * 两条解析路径**都跑**，然后按「星期|起始节|结束节|课程名」合并去重：
 *   1) 正方 jsxsd 专用：<table id="timetable"> + .kbcontent 块 + <font title=…>
 *   2) 通用表格：展开 rowspan/colspan 的二维网格，按「星期表头 × 节次行头」取格
 *
 * 为什么必须合并：旧实现是「正方解析出非空就直接 return」，于是只要正方那条路径漏了
 * 任何一类行（例如行头写成阿拉伯数字、首格是 td、单元格带 colspan、kbcontent 用了单引号），
 * 就会整行/整列丢课，而通用路径本来能补上 —— 用户看到的现象就是「从教务系统导入缺课」。
 */
final class JwParser {

    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t([dh])([^>]*)>(.*?)</t\\1>");
    private static final Pattern CLASS_ATTR = Pattern.compile("(?is)class\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FONT_TITLED = Pattern.compile(
            "(?is)<font[^>]*title\\s*=\\s*[\"'][^\"']*[\"'][^>]*>.*?</font>");

    private JwParser() {
    }

    /** 解析结果 + 可读日志（便于在 logcat 里一眼看出两条路径各解析了多少） */
    static final class ParseResult {
        final List<Course> courses = new ArrayList<>();
        /** 页面备注行里出现、但**没有具体节次**的课（如「劳动教育 1-20周」），无法排进课表 */
        final List<String> unscheduled = new ArrayList<>();
        int zhengfang = -1;      // -1 表示没走正方路径
        int generic = -1;
        int merged = 0;          // 靠合并补回来的门数
        final int[] perDay = new int[7];
        String log = "";

        void count() {
            java.util.Arrays.fill(perDay, 0);
            for (Course c : courses) {
                if (c.day >= 0 && c.day < 7) perDay[c.day]++;
            }
        }
    }

    /** 最近一次 parse 的"无节次课程"，供调用方提示用户（解析都在后台单线程串行） */
    private static volatile List<String> lastUnscheduled = new ArrayList<>();

    static List<String> lastUnscheduled() {
        return lastUnscheduled;
    }

    static ParseResult parse(String html) {
        ParseResult r = new ParseResult();
        if (html == null || html.isEmpty()) {
            r.log = "html 为空";
            return r;
        }
        // 0) JSON 课表（新版正方 jwglxt 的 xskbcx 接口 / 部分学校的 vetbkcx 都返回这种结构）
        List<Course> json = parseJsonCourseTable(html);
        if (!json.isEmpty()) {
            r.courses.addAll(json);
            r.zhengfang = -1;
            r.generic = -1;
            r.unscheduled.addAll(findUnscheduled(html));
            lastUnscheduled = r.unscheduled;
            r.count();
            r.log = "json=" + r.courses.size() + " perDay=" + java.util.Arrays.toString(r.perDay);
            return r;
        }

        List<Course> zf = new ArrayList<>();
        if (html.contains("kbcontent") || html.contains("id=\"timetable\"") || html.contains("id='timetable'")) {
            zf = parseZhengfang(html);
            r.zhengfang = zf.size();
            if (html.contains("<!--frame-->")) {
                // WebView 抓的是「主文档 + 同源 iframe」拼接页：正方表可能在后面那段里
                int at = html.indexOf("<!--frame-->");
                List<Course> zf2 = parseZhengfang(html.substring(at));
                if (zf2.size() > zf.size()) {
                    zf = zf2;
                    r.zhengfang = zf.size();
                }
            }
        }
        List<Course> gen = parseGeneric(html);
        r.generic = gen.size();

        List<Course> picked;
        if (zf.isEmpty()) {
            picked = gen;
        } else if (gen.isEmpty()) {
            picked = zf;
        } else {
            picked = merge(zf, gen);
            r.merged = picked.size() - zf.size();
        }
        r.courses.addAll(picked);
        r.unscheduled.addAll(findUnscheduled(html));
        lastUnscheduled = r.unscheduled;
        r.count();
        r.log = "zf=" + r.zhengfang + " gen=" + r.generic + " final=" + r.courses.size()
                + " (+" + Math.max(r.merged, 0) + " by merge) perDay=" + java.util.Arrays.toString(r.perDay)
                + (r.unscheduled.isEmpty() ? "" : " unscheduled=" + r.unscheduled);
        return r;
    }

    /**
     * 备注行里"没有节次"的课：正方课表最后一行是
     *   <td colspan="7">劳动教育 1-20周;大学物理实验A（一） 张明昆 4-5周;…</td>
     * 这类课在网格里没有位置，排不进课表。抓出来如实告知，而不是静默消失。
     */
    private static List<String> findUnscheduled(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<td[^>]*colspan\\s*=\\s*[\"']?([6-9]|1[0-9])[\"']?[^>]*>(.*?)</td>")
                .matcher(html);
        while (m.find()) {
            String text = clean(m.group(2));
            if (text.isEmpty()) continue;
            for (String seg : text.split("[;；]")) {
                String s = seg.trim();
                if (s.length() < 2) continue;
                if (s.matches("^[0-9\\s:：\\-]+$")) continue;
                if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /**
     * 合并：primary（正方，字段最准）为基底，extra（通用表格）**只补课**。
     *
     * 三条规则，缺一条都会出问题：
     *   1. 同格同名 → 周次取**并集**（同一门课被排成 1-12 周 + 13-16 周两段时，不能丢前一段）
     *   2. 同名但不在这格 → 正方已经解析出这门课，extra 的多半是同一格的重复/错位，丢弃
     *   3. 只剩「课名在正方结果里一次都没出现过」的条目才补 —— 那才是真正被整行/整列丢掉的课
     *
     * 判据必须严：实测 jw_live.html zf=19 / gen=26，无脑合并会变成 45 门（从缺课直接变多课）。
     */
    private static List<Course> merge(List<Course> primary, List<Course> extra) {
        Map<String, Course> map = new LinkedHashMap<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Course c : primary) {
            String k = keyOf(c);
            Course same = map.get(k);
            if (same != null) same.weeks.or(c.weeks);   // 同一格同名两段（1-12 周 + 13-16 周）
            else map.put(k, c);
            names.add(norm(c.name));
        }
        for (Course c : extra) {
            String n = norm(c.name);
            if (!plausibleCourseName(n)) continue;                // 整格文本/备注被当成课名时挡掉
            if (names.contains(n)) continue;
            if (looksLikeSameCourse(names, n)) continue;          // "大学外语（三）" vs "…(板块2B1)"
            map.put(keyOf(c), c);                                 // 同 key 不再合并周次：通用解析器默认 1-18，会污染
            names.add(n);
        }
        return new ArrayList<>(map.values());
    }

    /** 看似课名：2~24 字，不含分隔符/周次串（"劳动教育 1-20周;…" 这类整段文本会被挡掉） */
    private static boolean plausibleCourseName(String n) {
        if (n.length() < 2 || n.length() > 24) return false;
        if (n.contains(";") || n.contains("；") || n.contains("|")) return false;
        if (n.contains("-----")) return false;
        if (n.matches(".*\\d+\\s*[-~至]?\\s*\\d*\\s*周.*")) return false;
        if (n.matches("^[0-9\\s:：\\-~]+$")) return false;
        return true;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s　]", "").trim();
    }

    /** 只对够长的名字做子串比较，避免"体育"/"数学"这类短名把别的课误判成同一门 */
    private static boolean looksLikeSameCourse(java.util.Set<String> names, String candidate) {
        if (candidate.length() < 4) return false;
        for (String b : names) {
            if (b.length() < 4) continue;
            if (b.contains(candidate) || candidate.contains(b)) return true;
        }
        return false;
    }

    private static String keyOf(Course c) {
        return c.day + "|" + c.startNode + "|" + c.endNode + "|" + c.name.trim();
    }

    // ── JSON 课表（jwglxt / vetbkcx）───────────────────────────────────────────

    /**
     * 正方新版（jwglxt）的课表接口返回 JSON，不少学校直接把这个 JSON 贴出来：
     *   {"kbList":[{"kcmc":"高等数学","xm":"张三","cdmc":"A101","xqjmc":"星期一",
     *               "jcs":"1-2节","zcd":"1-16周","jcor":"1-2"}, …]}
     * 也兼容 {"allUnitLessonList":[…]}（部分学校 vetbkcx）。
     */
    static List<Course> parseJsonCourseTable(String text) {
        List<Course> out = new ArrayList<>();
        if (text == null) return out;
        boolean looksJson = text.contains("\"kbList\"") || text.contains("\"allUnitLessonList\"")
                || text.contains("\"kcmc\"");
        if (!looksJson) return out;
        Matcher m = Pattern.compile("\\{[^{}]*\"kcmc\"[^{}]*\\}").matcher(text);
        while (m.find()) {
            String obj = m.group();
            String name = jsonStr(obj, "kcmc");
            if (name.isEmpty()) continue;
            Course c = new Course();
            c.name = name;
            c.teacher = jsonStr(obj, "xm");
            if (c.teacher.isEmpty()) c.teacher = jsonStr(obj, "jsxm");
            c.room = jsonStr(obj, "cdmc");
            if (c.room.isEmpty()) c.room = jsonStr(obj, "jxdd");
            String dayName = jsonStr(obj, "xqjmc");
            if (dayName.isEmpty()) dayName = jsonStr(obj, "xqj");
            c.day = dayFromAny(dayName);
            if (c.day < 0) c.day = 0;
            String jcs = jsonStr(obj, "jcs");
            if (jcs.isEmpty()) jcs = jsonStr(obj, "jc");
            if (jcs.isEmpty()) jcs = jsonStr(obj, "jcor");
            int[] nodes = nodesFromHeader(jcs);
            if (nodes == null) nodes = new int[]{0, 1};
            c.startNode = nodes[0];
            c.endNode = nodes[1];
            String zcd = jsonStr(obj, "zcd");
            if (zcd.isEmpty()) zcd = jsonStr(obj, "zcmc");
            parseWeeks(zcd, c.weeks);
            if (c.weeks.isEmpty()) for (int w = 1; w <= 18; w++) c.weeks.set(w);
            c.color = colorOf(c.name);
            out.add(c);
        }
        return out;
    }

    /** 从 JSON 片段取字符串值（不引 JSON 库；值里不含转义引号时足够用） */
    private static String jsonStr(String obj, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
        return m.find() ? m.group(1).trim() : "";
    }

    /** "星期一" / "周一" / "礼拜一" / "1" / "Mon" → 0..6 */
    private static int dayFromAny(String s) {
        int d = dayIndex(s);
        if (d >= 0) return d;
        int n = safeInt(s, -1);
        if (n >= 1 && n <= 7) return n - 1;
        return -1;
    }
    // ── 正方 jsxsd ──────────────────────────────────────────────────────────

    static List<Course> parseZhengfang(String html) {
        List<Course> out = new ArrayList<>();
        Matcher tm = Pattern.compile("(?is)<table[^>]*id\\s*=\\s*[\"']timetable[\"'][^>]*>(.*?)</table>")
                .matcher(html);
        String tableHtml = tm.find() ? tm.group(1) : html;

        List<Integer> dayByTh = new ArrayList<>();
        int[] carryNodes = null;
        int carryRows = 0;

        Matcher rm = TR.matcher(tableHtml);
        while (rm.find()) {
            String rowHtml = rm.group(1);
            // 表头行：含「星期」且能认出 >=3 个星期
            if (dayByTh.isEmpty() && rowHtml.contains("星期")) {
                Matcher hm = Pattern.compile("(?is)<th([^>]*)>(.*?)</th>").matcher(rowHtml);
                int found = 0;
                List<Integer> tmp = new ArrayList<>();
                while (hm.find()) {
                    int d = dayIndex(clean(hm.group(2)));
                    int span = Math.max(1, Math.min(7, attrInt(hm.group(1), "colspan")));
                    for (int k = 0; k < span; k++) tmp.add(d);
                    if (d >= 0) found++;
                }
                if (found < 3) {
                    // 表头可能写成 <td>
                    Matcher dm = Pattern.compile("(?is)<td([^>]*)>(.*?)</td>").matcher(rowHtml);
                    tmp.clear();
                    found = 0;
                    while (dm.find()) {
                        int d = dayIndex(clean(dm.group(2)));
                        int span = Math.max(1, Math.min(7, attrInt(dm.group(1), "colspan")));
                        for (int k = 0; k < span; k++) tmp.add(d);
                        if (d >= 0) found++;
                    }
                }
                if (found >= 3) dayByTh.addAll(tmp);
                continue;
            }
            if (dayByTh.isEmpty()) continue;

            // 数据行：首个单元格是节次行头（正方是 th，部分学校是 td）
            List<String[]> cells = cellsOf(rowHtml);   // [tag, attrs, inner]
            if (cells.isEmpty()) continue;
            String[] first = cells.get(0);
            int firstSpan = Math.max(1, attrInt(first[1], "rowspan"));
            int startIdx;
            int[] nodes;
            if (carryRows > 0 && carryNodes != null) {
                nodes = carryNodes;
                carryRows--;
                startIdx = 0;               // 首格是被 rowspan 占掉的行头位置，数据从第 0 个 td 起
            } else {
                nodes = nodesFromHeader(clean(first[2]));
                if (nodes == null) {
                    // 首格是节次行头的另一条路：整行文本里第一段就是「第X节」
                    nodes = nodesFromHeader(firstLine(clean(first[2])));
                }
                if (nodes != null) {
                    startIdx = 1;
                    if (firstSpan > 1) {
                        carryNodes = nodes;
                        carryRows = firstSpan - 1;
                    }
                } else {
                    // 首格不是节次：整行都当数据（列映射从头开始）
                    nodes = carryNodes;
                    startIdx = 0;
                }
            }
            if (nodes == null) continue;

            int col = startIdx == 1 ? 0 : 1;
            for (int i = 0; i < cells.size(); i++) {
                String[] cell = cells.get(i);
                int span = Math.max(1, Math.min(7, attrInt(cell[1], "colspan")));
                boolean isHead = i == 0 && startIdx == 1;
                int baseCol = col;
                col += span;
                if (isHead) continue;
                String inner = cell[2];
                List<String> blocks = splitKbBlocks(inner);
                for (int k = 0; k < span; k++) {
                    int day = dayOfTd(dayByTh, baseCol + k);
                    if (day < 0) continue;
                    for (String block : blocks) {
                        Course c = courseFromKbBlock(block);
                        if (c == null) continue;
                        c.day = day;
                        c.startNode = nodes[0];
                        c.endNode = nodes[1];
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    private static List<String[]> cellsOf(String rowHtml) {
        List<String[]> cells = new ArrayList<>();
        Matcher cm = CELL.matcher(rowHtml);
        while (cm.find()) {
            String tag = cm.group(1);
            String attrs = cm.group(2) == null ? "" : cm.group(2);
            cells.add(new String[]{tag, attrs, cm.group(3) == null ? "" : cm.group(3)});
        }
        return cells;
    }

    /**
     * 数据格在行内的下标 → 星期。
     *
     * cellsOf() 把 th 和 td **都**收进来（因为节次行头在不同学校分别是 th / td），
     * 所以数据格下标与表头 th 下标是**一一对齐**的（两者都含最左边那一列）。
     * 早期实现按「1 个 th + N 个 td」的形态用了 +1，一旦行头是 td 就会整体错一天
     * （周一整列被丢、其余前移）—— 那正是「缺课」的另一类。
     */
    private static int dayOfTd(List<Integer> dayByTh, int cellIndex) {
        if (cellIndex >= 0 && cellIndex < dayByTh.size() && dayByTh.get(cellIndex) >= 0) {
            return dayByTh.get(cellIndex);
        }
        // 兜底：有的版本数据行省掉了节次列，首格就是周一
        int alt = cellIndex + 1;
        if (alt >= 0 && alt < dayByTh.size() && dayByTh.get(alt) >= 0) return dayByTh.get(alt);
        return -1;
    }

    // ── 节次行头 ────────────────────────────────────────────────────────────

    /**
     * 行头 → {起始节, 结束节}（0 基）。
     * 必须只看**第一段**：正方行头是「第一二节&lt;br&gt;08:10-09:50」，
     * 带着时间一起解析会把 08 当成节次。支持：
     *   第一二节 / 第九十节 / 第十一十二节（中文）
     *   第1-2节 / 1-2节 / 1、2节 / 1~2   （阿拉伯数字）
     *   1 / 01 / 第一节                    （单节）
     * 旧实现只认「第…节」，于是行头写成纯数字的教务系统会整行丢 —— 这正是「缺课」的一类。
     */
    static int[] nodesFromHeader(String head) {
        if (head == null) return null;
        String h = head.replace("\u00A0", " ").trim();
        if (h.isEmpty()) return null;
        String first = firstLine(h);
        int[] cn = nodesFromChinese(first);
        if (cn != null) return cn;
        int[] num = nodesFromDigits(first);
        if (num != null) return num;
        if (!first.equals(h)) {          // 第一段没认出来，再试整段（有的行头用空格连写）
            cn = nodesFromChinese(h.replace("\n", " "));
            if (cn != null) return cn;
            return nodesFromDigits(h.replace("\n", " "));
        }
        return null;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static int[] nodesFromChinese(String h) {
        int a0 = h.indexOf('第');
        int b0 = h.indexOf('节', a0 < 0 ? 0 : a0);
        String body = (a0 >= 0 && b0 > a0) ? h.substring(a0 + 1, b0) : h;
        // XIT's evening row mixes numbering systems: 第九十11节 = 9, 10, 11.
        if ("九十11".equals(body)) return new int[]{8,10};
        int[] cn = cnNumbers(body);
        if (cn == null || cn.length == 0) return null;
        int a = cn[0];
        int b = cn.length > 1 ? cn[1] : a;
        return clampNodes(a, b);
    }

    private static int[] nodesFromDigits(String h) {
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[-~—–至到,，、]\\s*(\\d{1,2})").matcher(h);
        if (m.find()) {
            return clampNodes(safeInt(m.group(1), -1), safeInt(m.group(2), -1));
        }
        Matcher m2 = Pattern.compile("(?:^|[^0-9:])(\\d{1,2})(?:\\s*节|[^0-9:]|$)").matcher(h);
        if (m2.find()) {
            int a = safeInt(m2.group(1), -1);
            return clampNodes(a, a);
        }
        return null;
    }

    private static int[] clampNodes(int a, int b) {
        if (a < 1 || a > 14) return null;
        int end = Math.max(a, Math.min(b < 1 ? a : b, 14));
        return new int[]{a - 1, end - 1};
    }

    /** 「一二」→{1,2}；「九十」→{9,10}；「十一十二」→{11,12}；「十三十四」→{13,14} */
    private static int[] cnNumbers(String s) {
        if (s == null || s.isEmpty()) return null;
        List<Integer> nums = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '十') {
                int n = 10;
                if (i + 1 < s.length() && cnDigit(s.charAt(i + 1)) > 0) {
                    n = 10 + cnDigit(s.charAt(i + 1));
                    i++;
                }
                nums.add(n);
            } else {
                int d = cnDigit(ch);
                if (d > 0) nums.add(d);
            }
            i++;
        }
        if (nums.isEmpty()) return null;
        int[] a = new int[nums.size()];
        for (int k = 0; k < a.length; k++) a[k] = nums.get(k);
        return a;
    }

    private static int cnDigit(char ch) {
        switch (ch) {
            case '一': return 1;
            case '二': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }

    // ── 单元格 → 课程 ───────────────────────────────────────────────────────

    /**
     * 一个 td 里可能有多门课，先按 kbcontent 块切，**再按块内的分隔线切段**。
     *
     * 正方每个格子通常有**两个** div：kbcontent1（隐藏，名/周次/教室）与 kbcontent（可见，含教师）。
     * 有可见块就只用可见块，避免一门课算成两门。
     *
     * class 属性**宽松**匹配：单引号、多类名（"kbcontent kbcontent-x"）、大写都认 ——
     * 旧实现写死 class="kbcontent"（双引号精确），任何变体都切不开，整格当一个块。
     *
     * 关键补充（实测 xskb_list.html）：同一格排多门课时，正方把**多门课塞进同一个
     * kbcontent 块**，用 `<br>----------------------<br>` 这种横线分隔：
     *   …创新创业基础…------…形势与政策（三）…------…国家安全教育…
     * 只按 kbcontent 切的话每格只出第一门，后两门（含用户报的「形势与政策（三）」）全丢。
     */
    private static List<String> splitKbBlocks(String cell) {
        List<int[]> visible = new ArrayList<>();
        List<int[]> hidden = new ArrayList<>();
        Matcher m = CLASS_ATTR.matcher(cell);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null || raw.isEmpty()) continue;
            boolean isVisible = false, isHidden = false;
            for (String tok : raw.split("\\s+")) {
                String t = tok.toLowerCase(Locale.ROOT);
                if (t.equals("kbcontent")) isVisible = true;
                else if (t.startsWith("kbcontent")) isHidden = true;
            }
            if (!isVisible && !isHidden) continue;
            int start = divStart(cell, m.start());
            int[] span = new int[]{start, m.end()};
            if (isVisible) visible.add(span);
            else hidden.add(span);
        }
        List<int[]> starts = visible.isEmpty() ? hidden : visible;
        List<String> out = new ArrayList<>();
        if (starts.isEmpty()) {
            if (!clean(cell).isEmpty()) addSegments(out, cell);
            return out;
        }
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i)[0];
            int e = i + 1 < starts.size() ? starts.get(i + 1)[0] : cell.length();
            if (e > s) addSegments(out, cell.substring(s, e));
        }
        return out;
    }

    /** 块内分隔线：正方的 `------`（3 个以上 -、—、_），或 <hr> */
    private static final Pattern SEG_SEP = Pattern.compile(
            "(?is)<hr[^>]*>|(?:<br\\s*/?>\\s*)?[-–—_]{3,}\\s*(?:<br\\s*/?>)?");

    private static void addSegments(List<String> out, String block) {
        String[] parts = SEG_SEP.split(block);
        if (parts.length <= 1) {
            if (!clean(block).isEmpty()) out.add(block);
            return;
        }
        for (String p : parts) {
            if (p != null && !clean(p).isEmpty()) out.add(p);
        }
    }

    /** 从 class 属性位置回退到所在标签的 '<'，块才有完整的 <div …>结构 */
    private static int divStart(String cell, int classPos) {
        int i = classPos;
        while (i > 0 && cell.charAt(i) != '<') i--;
        return i;
    }

    private static Course courseFromKbBlock(String block) {
        String name = firstFontText(block, null);
        if (name == null || name.trim().length() < 2) {
            // 课名常常是裸文本（不在 font 里）；把带 title 的 font 整段剥掉再取第一行
            name = firstPlainLine(block);
        }
        if (name == null) return null;
        // font 里可能含 <br>（如「大学外语（三）<br>(大学英语（三）板块2B1)」），只取第一行
        name = firstLine(name).trim();
        if (name.length() < 2) return null;
        if (name.length() > 40) return null;
        if (name.matches("^[0-9\\s:：\\-~]+$")) return null;

        Course c = new Course();
        c.name = name;
        String weeks = firstFontText(block, "周次(节次)");
        String room = firstFontText(block, "教室");
        String teacher = firstFontText(block, "教师");
        if (weeks == null) weeks = firstFontText(block, "周次");
        if (room == null) room = firstFontText(block, "上课地点");
        if (teacher == null) teacher = firstFontText(block, "任课教师");
        c.room = room == null ? "" : room.trim();
        c.teacher = teacher == null ? "" : teacher.trim();
        c.color = colorOf(c.name);
        if (weeks != null) {
            // 4-15(周) / 1-16周 / 单周 / 3,5,7(周) / 1-10,12,14,16
            Matcher wm = Pattern.compile("(\\d{1,2}(?:[-_,，]\\d{1,2})*)").matcher(weeks);
            if (wm.find()) {
                for (String seg : wm.group(1).split("[,，_]+")) {
                    int dash = seg.indexOf('-');
                    if (dash > 0) {
                        int a = safeInt(seg.substring(0, dash), -1);
                        int b = safeInt(seg.substring(dash + 1), -1);
                        if (a > 0 && b >= a) for (int w = a; w <= b; w++) c.weeks.set(w);
                    } else {
                        int a = safeInt(seg, -1);
                        if (a > 0) c.weeks.set(a);
                    }
                }
            }
        }
        if (c.weeks.isEmpty()) for (int w = 1; w <= 18; w++) c.weeks.set(w);
        return c;
    }

    /** 剥掉带 title 的 font（教师/教室/周次都在里面），剩下的裸文本第一行就是课名 */
    private static String firstPlainLine(String block) {
        String s = FONT_TITLED.matcher(block).replaceAll(" ");
        s = clean(s);
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.matches("^[0-9\\s:：\\-~至]+$")) continue;
            if (t.matches(".*\\d+\\s*[-_,，]?\\s*\\d*\\s*[周节].*")) continue;
            if (t.length() < 2) continue;
            return t;
        }
        return null;
    }

    private static String firstFontText(String block, String title) {
        if (title != null) {
            Matcher m = Pattern.compile("(?is)<font([^>]*title\\s*=\\s*[\"']" + Pattern.quote(title)
                    + "[\"'][^>]*)>(.*?)</font>").matcher(block);
            while (m.find()) {
                String attrs = m.group(1) == null ? "" : m.group(1);
                if (isHiddenFont(attrs)) continue;
                String tx = clean(m.group(2));
                if (!tx.isEmpty()) return tx;
            }
            return null;
        }
        Matcher m = Pattern.compile("(?is)<font([^>]*)>(.*?)</font>").matcher(block);
        while (m.find()) {
            String attrs = m.group(1) == null ? "" : m.group(1);
            if (attrs.toLowerCase(Locale.ROOT).contains("title")) continue;
            if (isHiddenFont(attrs)) continue;
            String tx = clean(m.group(2));
            if (!tx.isEmpty()) return tx;
        }
        return null;
    }

    /** 正方把辅助信息塞在 display:none 的 font 里：通知单编号/班级/备注/讲课学时/教学楼 */
    private static boolean isHiddenFont(String attrs) {
        if (attrs == null) return false;
        String a = attrs.replace(" ", "").toLowerCase(Locale.ROOT);
        if (a.contains("display:none")) return true;
        if (a.contains("name=\"wkxx\"") || a.contains("name=\"tzdbh\"") || a.contains("name=\"ktmcstr\"")
                || a.contains("name=\"bzstr\"") || a.contains("name=\"xsks\"") || a.contains("name=\"jxlmc\"")) {
            return true;
        }
        return a.contains("title=\"通知单编号\"") || a.contains("title=\"班级\"")
                || a.contains("title=\"备注\"") || a.contains("title=\"教学楼\"");
    }

    // ── 通用表格 ────────────────────────────────────────────────────────────

    static List<Course> parseGeneric(String html) {
        List<Course> best = new ArrayList<>();
        Matcher tm = TABLE.matcher(html);
        while (tm.find()) {
            List<Course> part = parseTable(tm.group(1));
            if (part.size() > best.size()) best = part;   // 取解析出课程最多的那张表
        }
        return best;
    }

    private static List<Course> parseTable(String tableHtml) {
        List<Course> out = new ArrayList<>();
        Matcher rm = TR.matcher(tableHtml);
        List<List<String>> rows = new ArrayList<>();
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = CELL.matcher(rm.group(1));
            while (cm.find()) {
                String attrs = cm.group(2) == null ? "" : cm.group(2);
                int rs = attrInt(attrs, "rowspan");
                int cs = attrInt(attrs, "colspan");
                cells.add(cm.group(3) + "\u0001" + Math.max(rs, 1) + "\u0001" + Math.max(cs, 1));
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) return out;

        // 展开 rowspan/colspan，得到等宽二维网格
        List<List<String>> grid = new ArrayList<>();
        Map<Integer, int[]> carry = new LinkedHashMap<>();   // col -> {剩余行数, colspan}
        Map<Integer, String> carryText = new LinkedHashMap<>();
        for (List<String> row : rows) {
            List<String> line = new ArrayList<>();
            int col = 0, i = 0;
            while (i < row.size() || !carry.isEmpty()) {
                if (carry.containsKey(col)) {
                    int[] st = carry.get(col);
                    String txt = carryText.getOrDefault(col, "");
                    for (int k = 0; k < st[1]; k++) line.add(txt);
                    int left = st[0] - 1;
                    if (left <= 0) { carry.remove(col); carryText.remove(col); }
                    else carry.put(col, new int[]{left, st[1]});
                    col += st[1];
                    continue;
                }
                if (i >= row.size()) break;
                String cell = row.get(i++);
                String[] parts = cell.split("\u0001");
                String text = parts.length > 0 ? parts[0] : "";
                int rs = parts.length > 1 ? safeInt(parts[1], 1) : 1;
                int cs = parts.length > 2 ? safeInt(parts[2], 1) : 1;
                for (int k = 0; k < cs; k++) line.add(text);
                if (rs > 1) {
                    for (int k = 0; k < cs; k++) {
                        carry.put(col + k, new int[]{rs - 1, 1});
                        carryText.put(col + k, text);
                    }
                }
                col += cs;
            }
            grid.add(line);
        }

        // 表头行（星期）
        int headerIdx = -1;
        Map<Integer, Integer> dayOfCol = new LinkedHashMap<>();
        for (int r = 0; r < grid.size() && headerIdx < 0; r++) {
            List<String> line = grid.get(r);
            for (int c = 0; c < line.size(); c++) {
                int d = dayIndex(clean(line.get(c)));
                if (d >= 0 && !dayOfCol.containsValue(d)) dayOfCol.put(c, d);
            }
            if (dayOfCol.size() >= 3) headerIdx = r;
            else dayOfCol.clear();
        }
        if (headerIdx < 0) return out;

        for (int r = headerIdx + 1; r < grid.size(); r++) {
            List<String> line = grid.get(r);
            int node = -1;
            int[] nodes = null;
            for (String s : line) {
                nodes = nodesFromHeader(clean(s));
                if (nodes != null) { node = nodes[0]; break; }
            }
            if (nodes == null || node < 0) continue;
            for (int c = 0; c < line.size(); c++) {
                Integer day = dayOfCol.get(c);
                if (day == null) continue;
                String raw = line.get(c) == null ? "" : line.get(c);
                if (raw.trim().isEmpty()) continue;
                if (dayIndex(clean(raw)) >= 0) continue;      // 表头文字
                out.addAll(parseCell(raw, day, nodes[0], nodes[1]));
            }
        }
        return out;
    }

    /**
     * 单元格 → 课程（可能是**多门**）。
     *
     * 各校在"同一格多课"上的写法完全不同，这里统一先切段再逐段解析：
     *   正方    ------（一串减号）
     *   强智/URP 空行、<hr>、独立 div
     *   有的学校 用「;」把多门课并在一行（但那种通常也带节次，前面已被过滤）
     * 旧实现只取第一行当课名、其余当教师/地点，一格两门课时第二门直接消失。
     */
    private static List<Course> parseCell(String raw, int day, int startNode, int endNode) {
        List<Course> out = new ArrayList<>();
        for (String seg : splitSegments(raw)) {
            Course c = parseSingleCourse(seg, day, startNode, endNode);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** 有标签走 HTML 切段，纯文本走行切段 */
    private static List<String> splitSegments(String raw) {
        if (clean(raw).isEmpty()) return new ArrayList<>();     // &nbsp; / 空表格子
        return raw.indexOf('<') >= 0 ? splitHtmlSegments(raw) : splitPlainSegments(clean(raw));
    }

    /**
     * 按**原始 HTML**切"同一格里的多门课"：
     *   <hr>、连续两个 <br>、3 个以上横线（正方用 ------）
     * 单看 clean 后的文本不行：div/td 边界会产出伪空行，会把一门课切碎。
     */
    private static List<String> splitHtmlSegments(String html) {
        List<String> out = new ArrayList<>();
        String[] parts = Pattern.compile("(?is)<hr[^>]*>|(?:<br\\s*/?>\\s*){2,}|[-–—_=]{3,}").split(html);
        for (String p : parts) {
            String t = clean(p);
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) {
            String t = clean(html);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 纯文本切段：空行 / 3 个以上横线 / 分号 */
    private static List<String> splitPlainSegments(String text) {
        List<String> segs = new ArrayList<>();
        if (text == null) return segs;
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            boolean sep = s.matches("^[-–—_=*]{3,}$") || s.isEmpty();
            if (sep) {
                if (cur.length() > 0) { segs.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(s);
        }
        if (cur.length() > 0) segs.add(cur.toString());
        if (segs.size() > 1) {
            List<String> split = new ArrayList<>();
            for (String s : segs) {
                for (String p : s.split("[;；]")) {
                    if (!p.trim().isEmpty()) split.add(p.trim());
                }
            }
            segs = split;
        }
        return segs;
    }

    /** 一段文本 → 一门课：第一行名字，其余按关键词/形状挑教师与地点 */
    private static Course parseSingleCourse(String text, int day, int startNode, int endNode) {
        String[] lines = text.split("\\r?\\n");
        String name = null;
        List<String> others = new ArrayList<>();
        for (String l : lines) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (name == null) { name = s; continue; }
            if (looksLikeWeeks(s)) continue;                 // 周次描述跳过
            others.add(s);
        }
        if (name == null || name.length() < 2) return null;
        if (name.length() > 30) return null;
        if (name.matches("^[0-9\\s:：\\-~至]+$")) return null;    // 纯时间
        if (dayIndex(name) >= 0) return null;

        Course c = new Course();
        c.name = name;
        String room = "", teacher = "";
        for (String s : others) {
            String byKeyword = pickByKeyword(s);
            if ("room".equals(byKeyword) && room.isEmpty()) room = s;
            else if ("teacher".equals(byKeyword) && teacher.isEmpty()) teacher = s;
        }
        for (String s : others) {
            if (!room.isEmpty() && !teacher.isEmpty()) break;
            if (looksLikeRoom(s) && room.isEmpty()) room = s;
            else if (teacher.isEmpty() && s.length() <= 12) teacher = s;
        }
        if (room.isEmpty() && !others.isEmpty()) room = others.get(others.size() - 1);
        c.room = room;
        c.teacher = teacher;
        c.day = day;
        c.startNode = startNode;
        c.endNode = Math.max(startNode, endNode);
        c.color = 4;
        for (String l : lines) {
            if (looksLikeWeeks(l)) parseWeeks(l, c.weeks);
        }
        if (c.weeks.isEmpty()) for (int w = 1; w <= 18; w++) c.weeks.set(w);
        return c;
    }

    /** 这一行像周次描述：含「周」且含数字（"1-16周" / "1-16周(单)" / "3,5,7周" / "第1-16周"） */
    private static boolean looksLikeWeeks(String s) {
        return s.contains("周") && s.matches(".*\\d.*");
    }

    private static boolean looksLikeRoom(String s) {
        return s.matches(".*(楼|室|馆|场|区|中心|[0-9]{3,}).*");
    }

    /** "教师：张三" / "地点：A101" 这种带标签的行 */
    private static String pickByKeyword(String s) {
        if (s.matches("^(教师|老师|任课教师)\\s*[：:].*")) return "teacher";
        if (s.matches("^(地点|教室|上课地点|场地|上课教室)\\s*[：:].*")) return "room";
        return "";
    }

    /**
     * 周次解析（各校写法差异极大）：
     *   1-16周 / 1-16(周) / 第1-16周 / 1~16 / 1-16周(单) / 1,3,5周 / 1-10,12,14,16周 / 单周 / 双周
     */
    private static void parseWeeks(String text, java.util.BitSet into) {
        if (text == null || !text.contains("周")) return;   // 没有「周」就不是周次：别把 B202 / 正心307 当周数
        boolean oddOnly = text.contains("单周") || text.matches(".*\\((单|单周)\\).*");
        boolean evenOnly = text.contains("双周") || text.matches(".*\\((双|双周)\\).*");
        Matcher m = Pattern.compile("(\\d{1,2}(?:\\s*[-~至,，、_]\\s*\\d{1,2})*)\\s*周").matcher(text);
        while (m.find()) {
            String grp = m.group(1);
            if (grp == null) continue;
            for (String seg : grp.split("[,，、_]+")) {
                String s = seg.trim();
                int dash = s.indexOf('-') >= 0 ? s.indexOf('-') : s.indexOf('~');
                if (dash > 0) {
                    int a = safeInt(s.substring(0, dash).trim(), -1);
                    int b = safeInt(s.substring(dash + 1).trim(), -1);
                    if (a > 0 && b >= a && b <= 60) {
                        for (int w = a; w <= b; w++) {
                            if (oddOnly && w % 2 == 0) continue;
                            if (evenOnly && w % 2 == 1) continue;
                            into.set(w);
                        }
                    }
                } else {
                    int a = safeInt(s, -1);
                    if (a > 0 && a <= 60) {
                        if (oddOnly && a % 2 == 0) continue;
                        if (evenOnly && a % 2 == 1) continue;
                        into.set(a);
                    }
                }
            }
        }
    }

    // ── 诊断 / 工具 ─────────────────────────────────────────────────────────

    static String diagnose(String html) {
        if (html == null || html.isEmpty()) return "页面是空的";
        int tables = count(html, "(?is)<table");
        int kb = count(html, "(?i)\\bkbcontent");
        int weekdays = count(html, "星期[一二三四五六日天]");
        int rows = count(html, "(?is)第[一二三四五六七八九十]+节");
        int numRows = count(html, "(?is)(?:^|>)\\s*\\d{1,2}\\s*(?:[-~至]\\s*\\d{1,2})?\\s*(?:节|<)");
        int iframes = count(html, "(?is)<iframe");
        String hint;
        if (tables == 0 && kb == 0) {
            hint = "当前页没有课表表格 —— 请先在这个网页里点到「学期理论课表」页面，再点确定";
        } else if (kb == 0 && weekdays < 3) {
            hint = "有表格但没有「星期」表头 —— 可能不是课表页，或被跨域 iframe 挡住";
        } else {
            hint = "结构看起来像课表，但一门课都没解析出来 —— 请把这一页的 HTML 存下来发给作者加规则";
        }
        return "HTML " + html.length() + " 字符 · table " + tables + " 个 · kbcontent " + kb
                + " 个 · 星期表头 " + weekdays + " · 中文节次行头 " + rows + " · 数字节次行头 " + numRows
                + " · iframe " + iframes + "\n" + hint;
    }

    private static int count(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static int dayIndex(String s) {
        if (s == null) return -1;
        String v = s.replace(" ", "").trim();
        if (v.isEmpty() || v.length() > 6) return -1;
        String[] cn = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < cn.length; i++) {
            if (v.contains("星期" + cn[i]) || v.contains("周" + cn[i]) || v.contains("礼拜" + cn[i])) return i;
        }
        if (v.contains("星期天") || v.contains("周天") || v.contains("礼拜天")) return 6;
        String u = v.toUpperCase(Locale.ROOT);
        String[] en = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        for (int i = 0; i < en.length; i++) if (u.contains(en[i])) return i;
        return -1;
    }

    static String clean(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|tr|td|th|li|hr)>", "\n");
        s = s.replaceAll("(?i)<hr\\s*/?>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t\\u00A0]+", " ");
        s = s.replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    static int attrInt(String attrs, String name) {
        Matcher m = Pattern.compile("(?i)" + name + "\\s*=\\s*[\"']?\\s*(\\d+)").matcher(attrs);
        return m.find() ? safeInt(m.group(1), 1) : 1;
    }

    static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 没有颜色信息时按课程名散列到宿主调色板（与 IcsCodec.colorOf 同一算法，这里内联以便纯 JVM 测试） */
    static int colorOf(String name) {
        if (name == null || name.isEmpty()) return 4;
        int h = Math.abs(name.hashCode());
        return 1 + (h % 15);
    }
}
