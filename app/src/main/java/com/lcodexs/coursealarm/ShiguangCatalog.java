package com.lcodexs.coursealarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Read only the index on entry; read and verify the selected adapter off the UI thread. */
final class ShiguangCatalog {
    private static final String ARCHIVE = "assets/shiguang/warehouse.zip";
    private static String modulePath;
    private static List<Adapter> catalog;

    static final class Adapter {
        final String school, name, description, url, sha, id, initial;
        final boolean localXit;
        private Adapter() {
            localXit = true; school = "厦门工学院"; id = "XIT"; initial = "X XM GXY XMGXY";
            name = "厦门工学院教务系统"; description = "学期理论课表 · 教学周历与节次时间";
            url = "https://xgjwxt.xit.edu.cn/jsxsd/"; sha = "";
        }
        Adapter(JSONObject row) throws Exception {
            localXit = false;
            school = row.getString("school"); name = row.getString("name"); id = row.getString("id");
            description = row.getString("description"); initial = row.getString("initial");
            url = row.getString("url"); sha = row.getString("sha");
            if (!sha.matches("[0-9a-f]{64}") || school.isEmpty() || name.isEmpty())
                throw new IOException("拾光适配索引条目无效");
        }
    }

    static synchronized void setModulePath(String path) {
        if (!java.util.Objects.equals(path, modulePath)) { modulePath = path; catalog = null; }
    }
    static synchronized List<Adapter> load() throws Exception {
        if (catalog != null) return catalog;
        JSONArray rows = new JSONObject(utf8(entry("catalog.json"))).getJSONArray("adapters");
        List<Adapter> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Adapter adapter = new Adapter(rows.getJSONObject(i));
            if (!ids.add(adapter.school + "/" + adapter.id)) throw new IOException("拾光适配索引条目重复");
            result.add(adapter);
        }
        if (result.isEmpty()) throw new IOException("拾光适配索引为空");
        result.add(new Adapter());
        return catalog = Collections.unmodifiableList(result);
    }
    static String script(Adapter a) throws Exception {
        byte[] bytes = entry("scripts/" + a.sha + ".js");
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            digest.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        }
        if (!a.sha.contentEquals(digest)) throw new IOException("拾光适配脚本校验失败");
        return utf8(bytes);
    }
    static String bridge() throws IOException {
        return bundledScript("bridge.js");
    }
    static String bundledScript(String name) throws IOException {
        try (ZipFile apk = openApk(); InputStream in = asset(apk, "assets/shiguang/" + name)) {
            return utf8(read(in));
        }
    }
    private static synchronized ZipFile openApk() throws IOException {
        if (modulePath == null) throw new IOException("无法定位模块安装包");
        return new ZipFile(modulePath);
    }
    private static InputStream asset(ZipFile apk, String path) throws IOException {
        ZipEntry item = apk.getEntry(path);
        if (item == null) throw new FileNotFoundException("模块缺少拾光适配资源");
        return apk.getInputStream(item);
    }
    private static byte[] entry(String path) throws IOException {
        try (ZipFile apk = openApk(); ZipInputStream archive = new ZipInputStream(asset(apk, ARCHIVE))) {
            for (ZipEntry e; (e = archive.getNextEntry()) != null; ) {
                if (e.getName().equals(path)) return read(archive);
            }
        }
        throw new IOException("模块缺少选定的拾光适配资源");
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
            if (out.size() + n > 1024 * 1024) throw new IOException("拾光适配资源过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
