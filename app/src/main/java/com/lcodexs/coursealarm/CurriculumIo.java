package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「导入课表 / 导出课表」两个菜单项的实际动作。
 *
 * 导出：Excel(.xlsx) 与 ICS(.ics)，落到系统「下载」目录并弹分享。
 * 导入：ICS 文件 / Excel 文件 / 教务系统（通用适配器 + 粘贴 HTML）。
 * 写入：走 CourseWriter → content://com.coloros.calendar/events（课表真正读的库）。
 */
final class CurriculumIo {
    private static final String T = CourseAlarmEntry.TAG;

    static final int REQ_ICS = 0xCA11;
    static final int REQ_XLSX = 0xCA12;
    /**
     * 系统文件选择器里「.ics / .xlsx 都能选」的那一次回调。
     *
     * 旧实现用 REQ_ICS 起选择器，再按 `req == REQ_ICS` 判定 ICS —— 于是**选 Excel 也会走
     * ICS 解析器**，必然解析失败。格式只能看文件本身（ImportFormat.detect），不能看 requestCode。
     */
    static final int REQ_FILE = 0xCA13;

    static final String MENU_IMPORT = "导入课表";
    static final String MENU_EXPORT = "导出课表";

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 教务配置在内存里带一次（避免每次重新输密码） */
    private static Map<String, String> jwConfig = new LinkedHashMap<>();

    private CurriculumIo() {
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    private static void options(Activity a, String title, List<String> opts,
                                java.util.function.IntConsumer cb) {
        if (!TransferUi.alive(a)) return;
        try { TransferUi.choose(a, title, opts, -1, index -> { if (index != null) cb.accept(index); }); }
        catch (Exception e) { TransferUi.failed(a, e); }
    }

    static void showExportMenu(Activity a) {
        if (a == null) return;
        List<String> opts = new ArrayList<>();
        opts.add("导出为 Excel（.xlsx）");
        opts.add("导出为 ICS 日历（.ics）");
        options(a, "导出课表", opts, idx -> {
            if (idx == 0) exportXlsx(a);
            else exportIcs(a);
        });
    }

    static void exportXlsx(Activity a) {
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(a);
                byte[] data = XlsxCodec.export(t, a);
                String name = "课程表_" + CourseWriter.today() + ".xlsx";
                Uri uri = FileIo.saveToDownloads(a, name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", data);
                final String msg = uri == null ? "导出失败" : "已导出 " + t.courses.size() + " 门课 → 下载/" + name;
                Logx.i(T, "export xlsx " + msg + " uri=" + uri);
                UI.post(() -> {
                    toast(a, msg);
                    if (uri != null) FileIo.share(a, uri,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "分享课程表");
                });
            } catch (Throwable e) {
                Logx.e(T, "exportXlsx failed", e);
                UI.post(() -> toast(a, "导出失败：" + e));
            }
        }, "lspx-export-xlsx").start();
    }

    static void exportIcs(Activity a) {
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(a);
                byte[] data = IcsCodec.export(t).getBytes("UTF-8");
                String name = "课程表_" + CourseWriter.today() + ".ics";
                Uri uri = FileIo.saveToDownloads(a, name, "text/calendar", data);
                final String msg = uri == null ? "导出失败" : "已导出 " + t.courses.size() + " 门课 → 下载/" + name;
                Logx.i(T, "export ics " + msg + " uri=" + uri);
                UI.post(() -> {
                    toast(a, msg);
                    if (uri != null) FileIo.share(a, uri, "text/calendar", "分享课程表");
                });
            } catch (Throwable e) {
                Logx.e(T, "exportIcs failed", e);
                UI.post(() -> toast(a, "导出失败：" + e));
            }
        }, "lspx-export-ics").start();
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    static void showImportMenu(Activity a) {
        if (a == null) return;
        Map<String, Runnable> entries = new LinkedHashMap<>();
        entries.put("拾光课表（教务系统导入）", () -> ShiguangImport.open(a));
        entries.put("从文件导入（选 .ics / .xlsx）", () -> pickFile(a, "*/*", REQ_FILE,
                "text/calendar", "application/octet-stream", "text/x-vcalendar",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"));
        entries.put("从「下载」目录直接选（推荐）", () -> pickFromDownloads(a));
        entries.put("粘贴课表 HTML / 表单直连（高级）", () -> importFromJw(a));
        List<String> labels = new ArrayList<>(entries.keySet());
        options(a, MENU_IMPORT, labels, index -> entries.get(labels.get(index)).run());
    }

    private static void pickFile(Activity a, String mime, int req, String... extraMimes) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            if (extraMimes != null && extraMimes.length > 0) i.putExtra(Intent.EXTRA_MIME_TYPES, extraMimes);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivityForResult(i, req);
        } catch (Throwable t) {
            Logx.e(T, "pickFile failed", t);
            toast(a, "打不开文件选择器：" + t);
        }
    }

    /**
     * 按文件路径导入（不经过系统选择器）。
     * 用途：① 自动化/证据脚本；② 文件选择器拿不到 Uri 时的兜底。
     */
    static void importFromFile(Activity a, java.io.File f, boolean icsHint) {
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(a);
                byte[] bytes = readFile(f);
                List<Course> list = parseBytes(bytes, f.getName(), null, t);
                Logx.i(T, "import from file " + f.getName() + " n=" + list.size()
                        + " (icsHint=" + icsHint + " 仅日志用，格式按内容判定)");
                UI.post(() -> preview(a, list, "文件 " + f.getName()));
            } catch (Throwable e) {
                Logx.e(T, "importFromFile failed", e);
                UI.post(() -> toast(a, "导入失败：" + e.getMessage()));
            }
        }, "lspx-import-file").start();
    }

    private static byte[] readFile(java.io.File f) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** 后台线程版：直接写入（给广播/自动化用，不弹 UI） */
    static void importAndWrite(Context ctx, java.io.File f, boolean ics, boolean replace, Runnable done) {
        importAndWrite(ctx, f, ics, replace, 0L, false, done);
    }

    /** termOverrideMs > 0 时用它当开学基准（恢复/迁移用：宿主的教学周锚点未必能从空表推出来） */
    static void importAndWrite(Context ctx, java.io.File f, boolean ics, boolean replace,
                               long termOverrideMs, boolean newTable, Runnable done) {
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(ctx);
                if (termOverrideMs > 0) {
                    t = t.copyWithTerm(termOverrideMs);
                    Logx.i(T, "importAndWrite term override → " + CourseRepo.fmt(termOverrideMs));
                }
                // 格式按内容判定（.xlsx / .ics / 课表 HTML 一把过），不再靠扩展名或入参猜
                List<Course> list = parseBytes(readFile(f), f.getName(), null, t);
                Logx.i(T, "importAndWrite " + f.getName() + " parsed=" + list.size()
                        + " replace=" + replace + " (icsHint=" + ics + ")");
                CourseMutations.importCourses(ctx, t, list,
                        newTable ? CourseMutations.Mode.NEW_TABLE : replace ? CourseMutations.Mode.REPLACE : CourseMutations.Mode.APPEND,
                        "课程表 " + (t.courseCalIds.size() + 1), result -> { if (done != null) done.run(); });
                return;
            } catch (Throwable e) {
                Logx.e(T, "importAndWrite failed", e);
            }
            if (done != null) UI.post(done);
        }, "lspx-import-write").start();
    }

    /** 由 Hooks 里 hook 的 onActivityResult 调用；返回 true 表示是我们处理的 */
    static boolean onActivityResult(Activity a, int req, int res, Intent data) {
        if (req != REQ_ICS && req != REQ_XLSX && req != REQ_FILE) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
            Logx.i(T, "import cancelled req=" + req + " res=" + res);
            return true;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(a);
                byte[] bytes = FileIo.readAll(a, uri);
                if (bytes == null) {
                    UI.post(() -> toast(a, "读不到文件"));
                    return;
                }
                String name = FileIo.displayName(a, uri);
                String mime = a.getContentResolver().getType(uri);
                List<Course> list = parseBytes(bytes, name, mime, t);
                Logx.i(T, "import parsed from " + name + " n=" + list.size());
                if (list.isEmpty()) {
                    UI.post(() -> toast(a, "这个文件里没解析出课程：" + name));
                    return;
                }
                UI.post(() -> preview(a, list, "文件 " + name));
            } catch (Throwable e) {
                Logx.e(T, "import failed", e);
                UI.post(() -> toast(a, "导入失败：" + e.getMessage()));
            }
        }, "lspx-import").start();
        return true;
    }

    /** 格式无关的统一解析入口：先判格式，再分发到对应解析器 */
    static List<Course> parseBytes(byte[] bytes, String name, String mime, TableCtx t) throws Exception {
        int fmt = ImportFormat.detect(bytes, name, mime);
        Logx.i(T, "import format=" + ImportFormat.name(fmt) + " (" + fmt + ") name=" + name
                + " mime=" + mime + " bytes=" + (bytes == null ? 0 : bytes.length));
        switch (fmt) {
            case ImportFormat.XLSX:
                return XlsxCodec.parse(new ByteArrayInputStream(bytes), t);
            case ImportFormat.ICS:
                return IcsCodec.parse(new ByteArrayInputStream(bytes), t);
            case ImportFormat.HTML:
            case ImportFormat.JSON:
                return JwClient.parseHtml(new String(bytes, "UTF-8"));
            case ImportFormat.XLS_OLE:
            case ImportFormat.UNKNOWN:
            default:
                throw new java.io.IOException(ImportFormat.rejectMessage(fmt, name));
        }
    }

    /**
     * 不经过系统文件选择器，直接列「下载」目录里看起来是课表的文件（.ics / .xlsx）。
     *
     * 为什么加这个：OPPO 的 PickerActivity 在部分场景下只列 MediaStore 里的文件（adb 推进去的看不到），
     * 而且它自己偶尔刷新很慢；给一个模块自己的列表，导入这条路就不依赖系统 UI 了。
     */
    static void pickFromDownloads(Activity a) {
        new Thread(() -> {
            List<String> names = new ArrayList<>();
            List<Uri> uris = new ArrayList<>();
            try {
                Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] proj = {android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME,
                        android.provider.MediaStore.Downloads.SIZE};
                try (android.database.Cursor c = a.getContentResolver()
                        .query(col, proj, null, null, android.provider.MediaStore.Downloads.DATE_ADDED + " DESC")) {
                    while (c != null && c.moveToNext()) {
                        String nm = c.getString(1);
                        if (nm == null) continue;
                        String low = nm.toLowerCase();
                        if (!low.endsWith(".ics") && !low.endsWith(".xlsx")) continue;
                        long size = c.getLong(2);
                        Uri u = android.content.ContentUris.withAppendedId(col, c.getLong(0));
                        // MediaStore 里可能留着已被 rm 掉的文件（条目还在、文件没了），
                        // 打开一下验证；失败就顺手删掉这条记录，别让用户点到幽灵条目
                        try (java.io.InputStream probe = a.getContentResolver().openInputStream(u)) {
                            if (probe == null) throw new java.io.IOException("null stream");
                            probe.read();
                        } catch (Throwable bad) {
                            Logx.w(T, "stale media entry, removing: " + nm);
                            try { a.getContentResolver().delete(u, null, null); } catch (Throwable ignored) { }
                            continue;
                        }
                        names.add(nm + "  (" + (size / 1024) + " KB)");
                        uris.add(u);
                    }
                }
            } catch (Throwable e) {
                Logx.w(T, "list downloads failed: " + e);
            }
            final List<String> fNames = names;
            final List<Uri> fUris = uris;
            Logx.i(T, "downloads files found=" + fNames.size() + " " + fNames);
            UI.post(() -> {
                if (fNames.isEmpty()) {
                    toast(a, "「下载」里没有 .ics / .xlsx —— 可以先从别处把文件存到下载目录");
                    return;
                }
                options(a, "选择「下载」里的课表文件", fNames, idx -> {
                    if (idx < 0 || idx >= fUris.size()) return;
                    importFromUri(a, fUris.get(idx), fNames.get(idx));
                });            });
        }, "lspx-list-downloads").start();
    }

    /** 打开宿主自己的「创建课表」页（兜底：连预置课程都失败时） */
    static void openHostCreateTable(Activity a) {
        HostTableCreator.openBlankCreateTable(a);
        toast(a, "已在宿主里打开「创建课表」——建好后回到课表页，再点 导入课表 选「替换现有课表」即可写进新表");
    }

    /** 按 Uri 导入（和系统选择器回调走同一段解析/预览逻辑） */
    static void importFromUri(Activity a, Uri uri, String label) {
        new Thread(() -> {
            try {
                TableCtx t = TableCtx.load(a);
                byte[] bytes = FileIo.readAll(a, uri);
                if (bytes == null) {
                    UI.post(() -> toast(a, "读不到这个文件"));
                    return;
                }
                // 这里以前靠 label.endsWith(".ics") 判断格式，而「下载目录」列表给的 label 是
                // "课程表_xxx.ics  (14 KB)" —— 永远不以 .ics 结尾，于是 ICS 反被当成 Excel 解析。
                List<Course> list = parseBytes(bytes, label, null, t);
                Logx.i(T, "import parsed from " + label + " n=" + list.size());
                UI.post(() -> {
                    if (list.isEmpty()) {
                        toast(a, "这个文件里没解析出课程：" + label);
                        return;
                    }
                    preview(a, list, label);
                });
            } catch (Throwable e) {
                Logx.e(T, "importFromUri failed", e);
                UI.post(() -> toast(a, "导入失败：" + e.getMessage()));
            }
        }, "lspx-import-uri").start();
    }

    /** 教务系统：先弹配置，再抓取，再预览 */
    static void importFromJw(Activity a) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{JwClient.K_LOGIN, "教务系统登录地址（http…）"});
        fields.add(new String[]{JwClient.K_TABLE, "课表页面地址"});
        fields.add(new String[]{JwClient.K_USER, "账号 / 学号"});
        fields.add(new String[]{JwClient.K_PASS, "密码", "secret"});
        fields.add(new String[]{JwClient.K_UFIELD, "账号字段名（默认 username）"});
        fields.add(new String[]{JwClient.K_PFIELD, "密码字段名（默认 password）"});

        Map<String, String> init = new LinkedHashMap<>(jwConfig);
        init.putIfAbsent(JwClient.K_UFIELD, "username");
        init.putIfAbsent(JwClient.K_PFIELD, "password");

        TransferUi.formAction(a, "从教务系统导入", fields, init, values -> {
            jwConfig = new LinkedHashMap<>(values);
            toast(a, "正在抓取教务系统…");
            new Thread(() -> {
                JwClient.Result r = JwClient.fetch(a, values);
                UI.post(() -> {
                    if (!r.ok) {
                        toast(a, r.message);
                        showPasteJw(a);
                        return;
                    }
                    preview(a, r.courses, "教务系统");
                });
            }, "lspx-jw").start();
        });
    }

    /** 抓不到时给个「粘贴 HTML」的后路 */
    private static void showPasteJw(Activity a) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{ "html", "把课表页 HTML 粘贴到这里" });
        TransferUi.formAction(a, "粘贴课表 HTML", fields, null, values -> {
            String html = values.get("html");
            if (html == null || html.trim().isEmpty()) return;
            new Thread(() -> {
                List<Course> list = JwClient.parseHtml(html);
                UI.post(() -> preview(a, list, "粘贴的 HTML"));
            }, "lspx-jw-paste").start();
        });
    }

    // ── 预览 → 写入 ──────────────────────────────────────────────────────────

    static void preview(Activity a, List<Course> list, String source) {
        if (!TransferUi.alive(a)) return;
        new Thread(() -> {
            TableCtx frozen = TableCtx.load(a);
            UI.post(() -> preview(a, list, source, frozen, ""));
        }, "course-import-preview").start();
    }

    static void preview(Activity a, List<Course> list, String source, TableCtx frozen, String note) {
        preview(a,list,source,frozen,note,null);
    }
    static void preview(Activity a, List<Course> list, String source, TableCtx frozen, String note, String proposedConfig) {
        if (!TransferUi.alive(a)) return;
        if (list == null || list.isEmpty()) {
            toast(a, source + "：没解析出课程");
            return;
        }
        if (frozen.calendarId <= 0) { toast(a, "请先选择目标课表"); return; }
        List<String> opts = new ArrayList<>();
        opts.add("替换现有课表（" + list.size() + " 门）");
        opts.add("追加到现有课表（" + list.size() + " 门）");
        opts.add("新建一张课表后导入（" + list.size() + " 门）");
        try {
            String summary = source + " · 共 " + list.size() + " 门\n目标课表：" + frozen.tableName
                    + (note.isEmpty() ? "" : "\n" + note);
            StringBuilder sample = new StringBuilder(summary);
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                Course c = list.get(i);
                sample.append("\n").append(c.name).append(" · ").append(Course.dayName(c.day))
                        .append(" 第").append(c.startNode + 1);
                if (c.endNode != c.startNode) sample.append("–").append(c.endNode + 1);
                sample.append("节");
            }
            TransferUi.choose(a, "导入预览", sample.toString(), opts, -1, idx -> {
                if (idx == null) return;
                CourseMutations.Mode mode = idx == 0 ? CourseMutations.Mode.REPLACE
                        : idx == 1 ? CourseMutations.Mode.APPEND : CourseMutations.Mode.NEW_TABLE;
                String name="课程表 " + (frozen.courseCalIds.size() + 1);
                if(proposedConfig==null) CourseMutations.importCourses(a,frozen,list,mode,name,null);
                else CourseMutations.importWithTiming(a,frozen,list,mode,name,proposedConfig);
            });
        } catch (Exception e) { TransferUi.failed(a, e); }
    }

    /**
     * 写完让课表刷新 —— **不 recreate Activity**（那会把用户从课表页顶回日历页，
     * 看着就像"导入没生效"）。改为：
     *   ① notifyChange 标准 + ColorOS 两个 authority（宿主的 ContentObserver 监听后者）
     *   ② 宿主进程内 LiveDataBus 发 refresh_course_table / db_change_*（宿主课表页立刻重读库）
     *   ③ 主页里模块挂的课表层重读一遍
     */
    static void refresh(Activity a) {
        try {
            HostBus.afterWrite(a);
            Logx.i(T, "refresh: notifyChange + bus + HostTablePage.refresh 已发出");
        } catch (Throwable t) {
            Logx.w(T, "refresh failed: " + t);
        }
    }

    static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
