package com.lcodexs.coursealarm;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教务系统导入（通用适配器）：只负责**取页面**。
 * HTML → 课程 的解析全部在 {@link JwParser}（纯 java.util，可在桌面上回归测试）。
 *
 * 借鉴 WakeUpSchedule（com.suda.yzune.wakeupschedule）的做法：登录 → 拉课表页 → 解析表格，
 * 但它的「每所学校一个适配器」放在它服务器上的配置，这里改成**用户可填的适配器**：
 *   登录地址 / 课表地址 / 账号 / 密码 / 表单字段名
 */
final class JwClient {
    private static final String T = CourseAlarmEntry.TAG;

    static final String K_LOGIN = "jw_login_url";
    static final String K_TABLE = "jw_table_url";
    static final String K_USER = "jw_user";
    static final String K_PASS = "jw_pass";
    static final String K_UFIELD = "jw_user_field";
    static final String K_PFIELD = "jw_pass_field";

    static final class Result {
        boolean ok;
        String message = "";
        List<Course> courses = new ArrayList<>();
    }

    private JwClient() {
    }

    static Result fetch(Context ctx, Map<String, String> cfg) {
        Result r = new Result();
        String login = trim(cfg.get(K_LOGIN));
        String table = trim(cfg.get(K_TABLE));
        String user = trim(cfg.get(K_USER));
        String pass = trim(cfg.get(K_PASS));
        String uField = orDefault(cfg.get(K_UFIELD), "username");
        String pField = orDefault(cfg.get(K_PFIELD), "password");

        if (login.isEmpty() || table.isEmpty()) {
            r.message = "请填写教务系统登录地址和课表地址";
            return r;
        }
        String html;
        try {
            String cookie = "";
            if (!user.isEmpty()) {
                String form = URLEncoder.encode(uField, "UTF-8") + "=" + URLEncoder.encode(user, "UTF-8")
                        + "&" + URLEncoder.encode(pField, "UTF-8") + "=" + URLEncoder.encode(pass, "UTF-8");
                String[] resp = post(login, form, cookie);
                cookie = resp[0];
                Logx.i(T, "jw login status body=" + Math.min(resp[1].length(), 200));
            }
            html = get(table, cookie);
            Logx.i(T, "jw timetable html len=" + html.length());
        } catch (Throwable t) {
            Logx.e(T, "jw fetch failed", t);
            r.message = "抓取失败：" + t;
            return r;
        }

        JwParser.ParseResult pr = JwParser.parse(html);
        r.courses = pr.courses;
        Logx.i(T, "jw parse " + pr.log);
        if (r.courses.isEmpty()) {
            r.message = "页面抓到了（" + html.length() + " 字符）但没解析出课程 —— "
                    + "可能是需要 JS 渲染、或课表在 iframe 里。可把课表页 HTML 用「粘贴 HTML」方式导入。";
            Logx.w(T, "jw parse empty, html head=" + head(html, 400));
            return r;
        }
        r.ok = true;
        r.message = "解析到 " + r.courses.size() + " 门课";
        return r;
    }

    /** 直接解析一段 HTML（给「粘贴 HTML」与文件导入用） */
    static List<Course> parseHtml(String html) {
        JwParser.ParseResult r = JwParser.parse(html);
        Logx.i(T, "jw parseHtml " + r.log);
        return r.courses;
    }

    /** 抓到的页面到底有什么：给「0 门课」时显示给人看，省得盲猜 */
    static String diagnose(String html) {
        return JwParser.diagnose(html);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private static String[] post(String url, String form, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try (OutputStream os = c.getOutputStream()) {
            os.write(form.getBytes("UTF-8"));
        }
        String setCookie = c.getHeaderField("Set-Cookie");
        String body = read(c);
        String newCookie = setCookie == null ? cookie : setCookie.split(";")[0];
        return new String[]{newCookie, body};
    }

    private static String get(String url, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        return read(c);
    }

    private static String read(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orDefault(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim();
    }
}
