package com.lcodexs.coursealarm;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 极简 xlsx 读写（不引第三方库，只用手写 OOXML + java.util.zip）。
 *  导出：一行表头 + 每课一行，字符串用 inlineStr，Excel / WPS 都能直接打开。
 *  导入：解 sheet1.xml，识别表头（课程名称/教师/地点/星期/开始节/结束节/周次），
 *        也接受「节次」「周次」写成 3-4 或 3,4,5-8 这种形式。
 */
final class XlsxCodec {
    private static final String T = CourseAlarmEntry.TAG;

    static final String[] HEADER = {
            "课程名称", "教师", "地点", "星期", "开始节", "结束节", "周次", "颜色"
    };

    private XlsxCodec() {
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    /**
     * 以「已知能打开的包」为模板导出：把 assets/xlsx_template.xlsx（openpyxl 生成，含
     * docProps / theme / styles 等 WPS 与 ColorOS 文档阅读器认的东西）整包复制，
     * 只替换 xl/worksheets/sheet1.xml。
     *
     * 为什么这么做：自己从零拼包时，openpyxl/pandas/Excel 都能读，但手机上的
     * andes.oplus.documentsreader(WPS 内核) 会显示成空白 —— 少 sheetPr/pageMargins/theme
     * 这类「非必需但被实现依赖」的部件。换成模板后一次就正常。
     */
    static byte[] export(TableCtx ctx, android.content.Context appCtx) {
        try {
            byte[] tpl = TemplateXlsx.bytes();
            if (tpl.length > 0) {
                // 模板里两张表：sheet1=「课表」网格（版式照抄教务系统导出的 xls），
                // sheet2=「课程明细」纯表格（也承担回灌解析）。
                java.util.Map<String, String> tplText = readTextEntries(tpl);
                String gridTpl = tplText.get("xl/worksheets/sheet1.xml");
                String detailTpl = tplText.get("xl/worksheets/sheet2.xml");
                java.util.Map<String, byte[]> replace = new java.util.LinkedHashMap<>();
                if (gridTpl != null) {
                    String grid = fillGrid(gridTpl, ctx);
                    replace.put("xl/worksheets/sheet1.xml", grid.getBytes("UTF-8"));
                }
                if (detailTpl != null) {
                    String detail = appendDetailRows(detailTpl, ctx);
                    replace.put("xl/worksheets/sheet2.xml", detail.getBytes("UTF-8"));
                }
                byte[] out = repack(new ByteArrayInputStream(tpl), replace);
                Logx.i(T, "xlsx export via template bytes=" + out.length + " tpl=" + tpl.length
                        + " grid=" + (gridTpl != null) + " detail=" + (detailTpl != null));
                if (out.length > 0) return out;
            } else {
                Logx.w(T, "TemplateXlsx empty");
            }
        } catch (Throwable t) {
            Logx.w(T, "xlsx template path failed, fallback: " + t);
        }
        return export(ctx);
    }

    /** 读模板里的文本部件，供占位符替换 */
    private static java.util.Map<String, String> readTextEntries(byte[] zip) throws Exception {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!n.endsWith(".xml") && !n.endsWith(".rels")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int k;
                while ((k = zin.read(buf)) > 0) bos.write(buf, 0, k);
                out.put(n, new String(bos.toByteArray(), "UTF-8"));
            }
        }
        return out;
    }

    /** 大节行：第 1-2 节 → 第 4 行，依次到 9-10 节 → 第 8 行 */
    private static int gridRow(int startNode) {
        int r = 4 + Math.max(0, Math.min(startNode, 9)) / 2;
        return Math.min(r, 8);
    }

    /**
     * 按教务系统的版式填格子：每个格子里每门课四行 ——
     * 课程名 / 教师 / 周次([周])[节次] / 教室，多门课之间空一行。
     */
    private static String fillGrid(String tpl, TableCtx ctx) {
        String title = "学生个人课表";
        java.util.TreeMap<String, StringBuilder> cells = new java.util.TreeMap<>();
        StringBuilder note = new StringBuilder("：");
        for (Course c : ctx.courses) {
            String key = "R" + gridRow(c.startNode) + "C" + (c.day + 2);
            StringBuilder sb = cells.get(key);
            if (sb == null) {
                sb = new StringBuilder();
                cells.put(key, sb);
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(c.name).append('\n');
            if (c.teacher != null && !c.teacher.isEmpty()) sb.append(c.teacher).append('\n');
            sb.append(c.weeksText()).append("([周])[")
                    .append(String.format("%02d-%02d", c.startNode + 1, c.endNode + 1))
                    .append("节]");
            if (c.room != null && !c.room.isEmpty()) sb.append('\n').append(c.room);
            if (note.length() > 1) note.append(';');
            note.append(c.name).append(' ').append(c.weeksText()).append("周");
        }

        String out = tpl;
        for (int r = 4; r <= 8; r++) {
            for (int c = 2; c <= 8; c++) {
                String token = "@@R" + r + "C" + c + "@@";
                StringBuilder sb = cells.get("R" + r + "C" + c);
                out = out.replace(token, sb == null ? "" : xmlEsc(sb.toString()));
            }
        }
        out = out.replace("@@NOTE@@", xmlEsc(note.toString()));
        // 标题也走占位符：模板里 A1 不合并、字号 11 —— 这台手机的阅读器不渲染合并单元格里的文字，
        // 之前标题空白就是这个原因（实测）。
        out = out.replace("@@TITLE@@", xmlEsc("学生个人课表"));
        return out;
    }

    /** 明细表：保留模板里的表头和列宽，往 sheetData 里追加数据行 */
    private static String appendDetailRows(String tpl, TableCtx ctx) {
        StringBuilder rows = new StringBuilder();
        int r = 2;
        for (Course c : ctx.courses) {
            rows.append(rowInline(r++, new String[]{
                    c.name, c.teacher, c.room, Course.dayName(c.day),
                    String.valueOf(c.startNode + 1), String.valueOf(c.endNode + 1),
                    c.weeksText(), String.valueOf(c.color)
            }));
        }
        int end = tpl.lastIndexOf("</sheetData>");
        if (end < 0) return tpl;
        return tpl.substring(0, end) + rows + tpl.substring(end);
    }

    /** 模板里那套 worksheet 骨架（sheetPr/sheetViews/cols/pageMargins），单元格用 inlineStr */
    private static String buildSheetXml(TableCtx ctx) {
        StringBuilder rows = new StringBuilder();
        rows.append(rowInline(1, HEADER));
        int r = 2;
        for (Course c : ctx.courses) {
            rows.append(rowInline(r++, new String[]{
                    c.name, c.teacher, c.room, Course.dayName(c.day),
                    String.valueOf(c.startNode + 1), String.valueOf(c.endNode + 1),
                    c.weeksText(), String.valueOf(c.color)
            }));
        }
        int total = r - 1;
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetPr><outlinePr summaryBelow=\"1\" summaryRight=\"1\"/><pageSetUpPr/></sheetPr>"
                + "<dimension ref=\"A1:H" + total + "\"/>"
                + "<sheetViews><sheetView workbookViewId=\"0\"><selection activeCell=\"A1\" sqref=\"A1\"/></sheetView></sheetViews>"
                + "<sheetFormatPr baseColWidth=\"8\" defaultRowHeight=\"15\"/>"
                + "<cols><col width=\"22\" customWidth=\"1\" min=\"1\" max=\"8\"/></cols>"
                + "<sheetData>" + rows + "</sheetData>"
                + "<pageMargins left=\"0.75\" right=\"0.75\" top=\"1\" bottom=\"1\" header=\"0.5\" footer=\"0.5\"/>"
                + "</worksheet>";
    }

    private static String rowInline(int index, String[] cells) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(index).append("\">");
        for (int c = 0; c < cells.length; c++) {
            String v = cells[c] == null ? "" : cells[c];
            sb.append("<c r=\"").append(col(c)).append(index)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xmlEsc(v)).append("</t></is></c>");
        }
        return sb.append("</row>").toString();
    }

    /** 复制模板包，替换 sheet1.xml；所有条目 STORED 写入（避免 data descriptor 让 Excel 报错） */
    private static byte[] repack(InputStream template, java.util.Map<String, byte[]> replace) throws Exception {
        java.util.LinkedHashMap<String, byte[]> parts = new java.util.LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(template)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                parts.put(e.getName(), bos.toByteArray());
            }
        }
        parts.putAll(replace);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> en : parts.entrySet()) {
                byte[] data = en.getValue();
                ZipEntry ze = new ZipEntry(en.getKey());
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(data.length);
                ze.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data, 0, data.length);
                ze.setCrc(crc.getValue());
                z.putNextEntry(ze);
                z.write(data);
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] export(TableCtx ctx) {
        // 用标准 sharedStrings，不用 inlineStr：部分手机阅读器（WPS/系统预览）只认 sharedStrings，
        // 见到 inlineStr 会把整张表显示成空白（实测踩到）。
        List<String> pool = new ArrayList<>();
        java.util.HashMap<String, Integer> idx = new java.util.HashMap<>();

        StringBuilder rows = new StringBuilder();
        rows.append(row(1, HEADER, pool, idx, true));
        int r = 2;
        for (Course c : ctx.courses) {
            rows.append(row(r++, new String[]{
                    c.name, c.teacher, c.room, Course.dayName(c.day),
                    String.valueOf(c.startNode + 1), String.valueOf(c.endNode + 1),
                    c.weeksText(), String.valueOf(c.color)
            }, pool, idx, false));
        }
        int totalRows = r - 1;

        StringBuilder sst = new StringBuilder();
        sst.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sst.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(pool.size()).append("\" uniqueCount=\"").append(pool.size()).append("\">");
        for (String s : pool) {
            sst.append("<si><t xml:space=\"preserve\">").append(xmlEsc(s)).append("</t></si>");
        }
        sst.append("</sst>");

        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<dimension ref=\"A1:H" + totalRows + "\"/>"
                + "<sheetViews><sheetView workbookViewId=\"0\" tabSelected=\"1\"/></sheetViews>"
                + "<sheetFormatPr defaultRowHeight=\"18\"/>"
                + "<cols><col min=\"1\" max=\"8\" width=\"22\" customWidth=\"1\"/></cols>"
                + "<sheetData>" + rows + "</sheetData></worksheet>";

        // 很多阅读器（手机 WPS / 系统预览 / 在线预览）见到缺 styles.xml 的包会直接显示空白，
        // 所以带上最小的 styles.xml，并把表头设成粗体。
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                    + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"课程表\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                    + "</Relationships>");
            put(z, "xl/styles.xml", styles);
            put(z, "xl/sharedStrings.xml", sst.toString());
            put(z, "xl/worksheets/sheet1.xml", sheet);
        } catch (Throwable t) {
            Logx.e(T, "xlsx export failed", t);
        }
        Logx.i(T, "xlsx generated rows=" + totalRows + " bytes=" + bos.size());
        return bos.toByteArray();
    }

    /**
     * 写一个 zip 条目。
     *
     * 必须 **先 setSize 再写**：不设大小时 ZipOutputStream 会用「data descriptor」
     * （把长度写在数据块后面），Excel 对这种流的处理有 bug —— 打开时直接报
     * 「XML 错误 / 意外的输出结束」，然后把工作表整块丢弃（表现为「修复后还是空白」）。
     * openpyxl/pandas 能读，所以只有 Excel/WPS 会暴露这个坑。实测踩到。
     * 用 STORED + CRC 最稳，体积也就十几 KB。
     */
    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        byte[] data = content.getBytes("UTF-8");
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, 0, data.length);
        e.setCrc(crc.getValue());
        e.setTime(System.currentTimeMillis());
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /**
     * 一行单元格。字符串进 sharedStrings 池（t="s" + 索引），纯数字直接写 <v>，
     * 表头用样式 1（粗体）。
     */
    private static String row(int index, String[] cells, List<String> pool,
                              java.util.HashMap<String, Integer> idx, boolean bold) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(index).append("\">");
        for (int c = 0; c < cells.length; c++) {
            String v = cells[c] == null ? "" : cells[c];
            sb.append("<c r=\"").append(col(c)).append(index).append('"');
            if (bold) sb.append(" s=\"1\"");
            if (isNumber(v)) {
                sb.append("><v>").append(v).append("</v></c>");
            } else {
                Integer at = idx.get(v);
                if (at == null) {
                    at = pool.size();
                    pool.add(v);
                    idx.put(v, at);
                }
                sb.append(" t=\"s\"><v>").append(at).append("</v></c>");
            }
        }
        return sb.append("</row>").toString();
    }

    /**
     * 只有「可选负号 + 纯数字」才算数字单元格。
     * 注意 "4-15"（周次区间）**不是**数字 —— 之前放行了中间的 '-'，把 4-15 写进 <v>，
     * Excel 判定 XML 错误后整张工作表被丢弃，表现就是「打开是空白」。实测踩到。
     */
    private static boolean isNumber(String v) {
        if (v.isEmpty()) return false;
        int i = 0;
        if (v.charAt(0) == '-') {
            if (v.length() == 1) return false;
            i = 1;
        }
        for (; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static String col(int i) {
        StringBuilder sb = new StringBuilder();
        int n = i;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    /** 表头行下标：含「课程名称」且至少 3 个已知列名的那一行；没有返回 -1 */
    private static int headerIndex(List<Map<Integer, String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), 8); i++) {
            Map<Integer, String> row = rows.get(i);
            int hit = 0;
            boolean hasName = false;
            for (String v : row.values()) {
                if (v == null) continue;
                String s2 = v.trim();
                if (s2.contains("课程名称") || s2.contains("课程名")) hasName = true;
                for (String h : HEADER) if (s2.equals(h)) hit++;
            }
            if (hasName && hit >= 3) return i;
        }
        return -1;
    }

    static List<Course> parse(InputStream in, TableCtx ctx) throws Exception {
        List<Course> out = new ArrayList<>();
        // 一张工作簿可能有多个 sheet（我们的模板就是「课表」+「课程明细」）：
        // 全部读出来，挑第一个能认出表头（课程名称…）的那张。
        List<byte[]> sheets = new ArrayList<>();
        Map<Integer, String> shared = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    sheets.add(readAll(z));
                } else if (n.endsWith("xl/sharedStrings.xml")) {
                    shared = parseSharedStrings(readAll(z));
                }
            }
        } catch (Throwable t) {
            // 明确抛出去：以前只记日志返回空列表，用户看到的是「没解析出课程」，
            // 完全不知道其实选错了文件（例如把 .xls/.ics 当成 xlsx 送进来）。
            throw new IllegalArgumentException("不是有效的 .xlsx（解包失败）：" + t.getMessage(), t);
        }
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("这个 .xlsx 里没有工作表（xl/worksheets/sheet*.xml）");
        }
        byte[] sheet = null;
        for (byte[] s : sheets) {
            List<Map<Integer, String>> probe = parseRows(s, shared);
            if (headerIndex(probe) >= 0) {
                sheet = s;
                Logx.i(T, "xlsx: picked worksheet with header, sheets=" + sheets.size());
                break;
            }
        }
        if (sheet == null) {
            sheet = sheets.get(0);
            Logx.w(T, "xlsx: no sheet with known header, using first of " + sheets.size());
        }

        List<Map<Integer, String>> rows = parseRows(sheet, shared);
        if (rows.isEmpty()) return out;

        // 找表头行
        int headerRow = -1;
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(rows.size(), 8); i++) {
            Map<Integer, String> r = rows.get(i);
            boolean hasName = false;
            for (Map.Entry<Integer, String> en : r.entrySet()) {
                String v = en.getValue() == null ? "" : en.getValue().trim();
                if (v.contains("课程") || v.contains("名称")) hasName = true;
            }
            if (hasName) {
                headerRow = i;
                for (Map.Entry<Integer, String> en : r.entrySet()) {
                    String k = en.getValue() == null ? "" : en.getValue().trim();
                    if (!k.isEmpty()) cols.put(k, en.getKey());
                }
                break;
            }
        }
        if (headerRow < 0) {
            Logx.w(T, "xlsx: header row not found");
            return out;
        }
        Logx.i(T, "xlsx header=" + cols.keySet());

        for (int i = headerRow + 1; i < rows.size(); i++) {
            Map<Integer, String> r = rows.get(i);
            Course c = new Course();
            c.name = get(r, cols, "课程名称", "课程", "名称", "课程名");
            if (c.name.isEmpty()) continue;
            c.teacher = get(r, cols, "教师", "老师", "任课教师");
            c.room = get(r, cols, "地点", "教室", "上课地点");
            c.day = parseDay(get(r, cols, "星期", "周几", "日"));
            c.startNode = safeInt(get(r, cols, "开始节", "节次", "起始节"), 1) - 1;
            c.endNode = safeInt(get(r, cols, "结束节", "截止节"), c.startNode + 2) - 1;
            if (c.endNode < c.startNode) c.endNode = c.startNode;
            c.color = safeInt(get(r, cols, "颜色"), 4);
            String wk = get(r, cols, "周次", "教学周", "周数");
            if (!wk.isEmpty()) {
                for (String seg : wk.split("[,，;； ]+")) {
                    String s = seg.trim();
                    if (s.isEmpty()) continue;
                    int dash = s.indexOf('-');
                    if (dash > 0) {
                        int a = safeInt(s.substring(0, dash), -1);
                        int b = safeInt(s.substring(dash + 1), -1);
                        if (a > 0 && b >= a) for (int w = a; w <= b; w++) c.weeks.set(w);
                    } else {
                        int a = safeInt(s, -1);
                        if (a > 0) c.weeks.set(a);
                    }
                }
            }
            if (c.weeks.isEmpty()) for (int w = 1; w <= ctx.weekCount; w++) c.weeks.set(w);
            out.add(c);
        }
        Logx.i(T, "xlsx parsed courses=" + out.size());
        return out;
    }

    private static String get(Map<Integer, String> row, Map<String, Integer> cols, String... names) {
        for (String n : names) {
            for (Map.Entry<String, Integer> e : cols.entrySet()) {
                if (e.getKey().replace(" ", "").contains(n)) {
                    String v = row.get(e.getValue());
                    if (v != null && !v.trim().isEmpty()) return v.trim();
                }
            }
        }
        return "";
    }

    private static int parseDay(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return 0;
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int i = 0; i < names.length; i++) {
            if (v.contains(names[i]) || v.contains(names[i].substring(1))) return i;
        }
        if (v.contains("一")) return 0;
        if (v.contains("二")) return 1;
        if (v.contains("三")) return 2;
        if (v.contains("四")) return 3;
        if (v.contains("五")) return 4;
        if (v.contains("六")) return 5;
        if (v.contains("日") || v.contains("七")) return 6;
        int n = safeInt(v, 1);
        return Math.max(0, Math.min(6, n - 1));
    }

    private static int safeInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim().replaceAll("[^0-9-]", ""));
        } catch (Throwable t) {
            return def;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static Map<Integer, String> parseSharedStrings(byte[] xml) {
        Map<Integer, String> map = new HashMap<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(xml), "UTF-8");
            int idx = -1;
            StringBuilder sb = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && "si".equals(p.getName())) {
                    idx++;
                    sb = new StringBuilder();
                } else if (e == XmlPullParser.TEXT && sb != null) {
                    sb.append(p.getText());
                } else if (e == XmlPullParser.END_TAG && "si".equals(p.getName()) && sb != null) {
                    map.put(idx, sb.toString());
                    sb = null;
                }
            }
        } catch (Throwable t) {
            Logx.w(T, "sharedStrings parse failed: " + t);
        }
        return map;
    }

    private static List<Map<Integer, String>> parseRows(byte[] xml, Map<Integer, String> shared) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(xml), "UTF-8");
            Map<Integer, String> cur = null;
            int cellCol = -1;
            int nextCol = 0;        // <c> 没有 r 属性时（r 在 OOXML 里是可选的）按出现顺序推列号
            String cellType = null;
            StringBuilder text = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                String name = p.getName();
                if (e == XmlPullParser.START_TAG) {
                    if ("row".equals(name)) {
                        cur = new HashMap<>();
                        nextCol = 0;
                    } else if ("c".equals(name) && cur != null) {
                        int parsed = colIndex(p.getAttributeValue(null, "r"));
                        cellCol = parsed >= 0 ? parsed : nextCol;
                        nextCol = cellCol + 1;
                        cellType = p.getAttributeValue(null, "t");
                        text = new StringBuilder();
                    } else if ("v".equals(name) && cellCol >= 0) {
                        text = new StringBuilder();
                        if (p.next() == XmlPullParser.TEXT) text.append(p.getText());
                    } else if ("t".equals(name) && cellCol >= 0) {
                        // inlineStr 与富文本 <is><r><t>…</t></r></is> 都走这里；
                        // 旧实现要求 t="inlineStr"，富文本单元格会被整格丢掉
                        if (p.next() == XmlPullParser.TEXT) {
                            if (text == null) text = new StringBuilder();
                            text.append(p.getText());
                        }
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    if ("c".equals(name) && cur != null && cellCol >= 0) {
                        String v = text == null ? "" : text.toString();
                        if ("s".equals(cellType)) {
                            try {
                                v = shared.getOrDefault(Integer.parseInt(v.trim()), v);
                            } catch (Throwable ignored) {
                            }
                        }
                        cur.put(cellCol, v);
                        cellCol = -1;
                        cellType = null;
                        text = null;
                    } else if ("row".equals(name) && cur != null) {
                        rows.add(cur);
                        cur = null;
                    }
                }
            }
        } catch (Throwable t) {
            Logx.e(T, "xlsx rows parse failed", t);
        }
        return rows;
    }

    private static int colIndex(String ref) {
        if (ref == null || ref.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') n = n * 26 + (ch - 'A' + 1);
            else break;
        }
        return n - 1;
    }
}
