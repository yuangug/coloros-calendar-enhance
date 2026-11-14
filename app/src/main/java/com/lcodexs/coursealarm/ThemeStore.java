package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 课表主题（背景）的持久化。
 *
 * 背景图片会被**复制到模块私有目录**（不是只存 Uri）：
 * 系统选择器给的 Uri 授权通常是临时的，重启宿主后就失效了，直接用来加载会变成空白。
 */
final class ThemeStore {
    private static final String T = CourseAlarmEntry.TAG;
    private static final String FILE = "lspx_course_theme";

    static final int TYPE_NONE = 0;
    static final int TYPE_SOLID = 1;
    static final int TYPE_GRADIENT = 2;
    static final int TYPE_IMAGE = 3;

    /** 预设纯色（柔和，保证深色文字可读） */
    static final int[] SOLIDS = {
            0xFFFFFFFF, 0xFFF3F6FF, 0xFFFFF6E9, 0xFFF0FAF3, 0xFFFDF0F5, 0xFF1E2430
    };
    /** 预设渐变（起点、终点） */
    static final int[][] GRADIENTS = {
            {0xFFEAF2FF, 0xFFFFF1F6},
            {0xFFE8F7F0, 0xFFEAF2FF},
            {0xFFFFF4E5, 0xFFFDEBF3},
            {0xFF2B3350, 0xFF4A3B63},
    };

    private static volatile SharedPreferences sp;

    private ThemeStore() {
    }

    static void init(Context ctx) {
        if (sp == null && ctx != null) {
            sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p(Context ctx) {
        if (sp == null) init(ctx);
        return sp;
    }

    static int type(Context c) {
        SharedPreferences s = p(c);
        return s == null ? TYPE_NONE : s.getInt("type", TYPE_NONE);
    }

    static int solidIndex(Context c) {
        SharedPreferences s = p(c);
        return s == null ? 0 : s.getInt("solid", 0);
    }

    static int gradientIndex(Context c) {
        SharedPreferences s = p(c);
        return s == null ? 0 : s.getInt("gradient", 0);
    }

    /** 背景图片文件（没有返回 null） */
    static File imageFile(Context c) {
        SharedPreferences s = p(c);
        String name = s == null ? null : s.getString("image", null);
        if (name == null || name.isEmpty()) return null;
        File f = new File(c.getFilesDir(), name);
        return f.isFile() ? f : null;
    }

    /** 背景图不透明度的百分比（0..100），文字可读性靠它 */
    static int imageAlpha(Context c) {
        SharedPreferences s = p(c);
        return s == null ? 100 : s.getInt("image_alpha", 100);
    }

    static void setType(Context c, int type) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().putInt("type", type).apply();
    }

    static void setSolid(Context c, int idx) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().putInt("solid", idx).putInt("type", TYPE_SOLID).apply();
    }

    static void setGradient(Context c, int idx) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().putInt("gradient", idx).putInt("type", TYPE_GRADIENT).apply();
    }

    static void setImageAlpha(Context c, int pct) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().putInt("image_alpha", Math.max(0, Math.min(100, pct))).apply();
    }

    /** 背景图上的遮罩浓度 0..230（越大背景越淡、课程文字越清楚） */
    static int scrim(Context c) {
        SharedPreferences s = p(c);
        return s == null ? 120 : s.getInt("scrim", 120);
    }

    static void setScrim(Context c, int v) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().putInt("scrim", Math.max(0, Math.min(230, v))).apply();
    }

    /**
     * 把用户选的图片复制进 filesDir，返回是否成功。
     * 每次都写同一个名字（theme_bg），顺手删掉上一种扩展名的旧文件。
     */
    static boolean importImage(Context c, Uri uri) {
        String ext = "png";
        try {
            String mime = c.getContentResolver().getType(uri);
            if (mime != null) {
                if (mime.contains("gif")) ext = "gif";
                else if (mime.contains("webp")) ext = "webp";
                else if (mime.contains("jpeg") || mime.contains("jpg")) ext = "jpg";
            }
            String name = c.getContentResolver().getType(uri);   // no-op，仅用于日志
            Logx.i(T, "theme import mime=" + name + " ext=" + ext);
        } catch (Throwable ignored) {
        }

        File dir = c.getFilesDir();
        for (String old : new String[]{"theme_bg.png", "theme_bg.gif", "theme_bg.jpg", "theme_bg.webp"}) {
            File f = new File(dir, old);
            if (f.isFile()) f.delete();
        }
        String target = "theme_bg." + ext;
        File out = new File(dir, target);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return false;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            SharedPreferences s = p(c);
            if (s != null) s.edit().putString("image", target).putInt("type", TYPE_IMAGE).apply();
            Logx.i(T, "theme image saved " + target + " " + out.length() + " bytes");
            return true;
        } catch (Throwable e) {
            Logx.e(T, "theme importImage failed", e);
            return false;
        }
    }

    static void clear(Context c) {
        SharedPreferences s = p(c);
        if (s != null) s.edit().clear().apply();
    }
}
