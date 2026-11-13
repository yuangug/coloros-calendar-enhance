package com.lcodexs.coursealarm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入文件格式判定。
 *
 * 为什么需要它：入口只有一个系统文件选择器（用户选 .ics 或 .xlsx 都是同一次回调），
 * 之前靠 requestCode 猜格式 —— 回调恒为 REQ_ICS，于是**选 Excel 也会走 ICS 解析器**，
 * 必然「导入Excel课表解析失败」。
 *
 * 判定顺序：内容 magic（最硬）→ 扩展名 → MIME。
 * 本类只依赖 java.*，不碰 Android，便于在桌面 JVM 上直接回归测试。
 */
final class ImportFormat {
    static final int UNKNOWN = 0;
    static final int ICS = 1;
    static final int XLSX = 2;
    static final int HTML = 3;
    /** 正方新版（jwglxt）课表接口的 JSON（kbList），按课表页同一条解析链处理 */
    static final int JSON = 5;
    /** 老式 BIFF8 .xls（OLE 复合文档）：本模块不解析，但要能明确告诉用户 */
    static final int XLS_OLE = 4;

    private static final Pattern EXT = Pattern.compile(
            "\\.(xlsx|xlsm|xls|ics|ical|vcs|ifb|html?)\\b", Pattern.CASE_INSENSITIVE);

    private ImportFormat() {
    }

    static String name(int fmt) {
        switch (fmt) {
            case ICS: return "ICS 日历";
            case XLSX: return "Excel(.xlsx)";
            case HTML: return "HTML 课表页";
            case XLS_OLE: return "老式 Excel(.xls)";
            case JSON: return "教务 JSON 课表";
            default: return "未知格式";
        }
    }

    /**
     * @param data 文件内容（可为 null，只有名字时退化到扩展名判定）
     * @param name 显示名，可能带大小尾巴，如 "课程表_20261113.xlsx  (37 KB)"
     */
    static int detect(byte[] data, String name, String mime) {
        // 1) 内容 magic —— 不看名字也不会认错
        if (data != null && data.length >= 4) {
            if ((data[0] & 0xFF) == 0x50 && (data[1] & 0xFF) == 0x4B) return XLSX;    // "PK\x03\x04"
            if ((data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF) return XLS_OLE; // D0 CF 11 E0
            String head = asText(data, 8192);
            if (head != null) {
                String up = head.toUpperCase(Locale.ROOT);
                if (up.contains("BEGIN:VCALENDAR")) return ICS;
                if (up.contains("\"KCMCLIST\"") || up.contains("\"KBLIST\"")) return JSON;
                if (up.contains("<TABLE") || up.contains("<HTML") || up.contains("<BODY")) return HTML;
                if (up.contains("KCMCLIST") || up.contains("\"KCMCLIST\"")) return JSON;
            }
        }
        // 2) 扩展名（从名字里抠，忽略 " (37 KB)" 这类尾巴）
        String ext = extensionOf(name);
        if (ext != null) {
            if (ext.equals(".xlsx") || ext.equals(".xlsm")) return XLSX;
            if (ext.equals(".xls")) return XLS_OLE;
            if (ext.equals(".ics") || ext.equals(".ical") || ext.equals(".vcs") || ext.equals(".ifb")) return ICS;
            if (ext.equals(".html") || ext.equals(".htm")) return HTML;
            if (ext.equals(".json")) return JSON;
        }
        // 3) MIME
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("calendar")) return ICS;
            if (m.contains("spreadsheetml") || m.contains("ms-excel") || m.contains("excel")) return XLSX;
            if (m.contains("html")) return HTML;
            if (m.contains("json")) return JSON;
        }
        return UNKNOWN;
    }

    /** 取出 ".xlsx" 这样的小写扩展名；没有返回 null */
    static String extensionOf(String name) {
        if (name == null) return null;
        Matcher m = EXT.matcher(name);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? null : ("." + last.toLowerCase(Locale.ROOT));
    }

    /** 给用户看的一句话（格式不对时说清是什么格式） */
    static String rejectMessage(int fmt, String name) {
        if (fmt == UNKNOWN) {
            return "认不出这个文件的格式：" + name + "（支持 .xlsx / .ics / 课表 HTML）";
        }
        if (fmt == XLS_OLE) {
            return "这是老式 Excel(.xls)，请在电脑上另存为 .xlsx 再导入：" + name;
        }
        return "";
    }

    /** 二进制（含 NUL）不当作文本，避免乱码进正则 */
    private static String asText(byte[] data, int max) {
        int n = Math.min(data.length, max);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) return null;
        }
        try {
            return new String(data, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
