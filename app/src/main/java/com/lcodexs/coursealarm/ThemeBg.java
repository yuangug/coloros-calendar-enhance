package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 课表页主题：背景图片（png / jpg / webp / gif 动图）+ 遮罩浓度。
 *
 * 做法：
 *   1. 全屏 ImageView 插到 android.R.id.content 的第 0 层（在所有宿主内容之下）
 *   2. 把课表页几个不透明容器（week_viewpager / day_viewpager / root_view）的背景设成半透明，
 *      否则白底会把背景整块挡住
 *   3. 背景图上再叠一层可调透明度的遮罩，保证课程文字仍然看得清
 *
 * gif 用 ImageDecoder（API 28+，能播动画），低版本退回 BitmapFactory（只显示第一帧）。
 */
final class ThemeBg {
    private static final String T = CourseAlarmEntry.TAG;
    private static final String TAG_BG = "lspx_theme_bg";
    private static final String TAG_SCRIM = "lspx_theme_scrim";
    static final int REQ_PICK = 0xCA20;

    private ThemeBg() {
    }

    /** 选图：系统文件选择器，只收图片 */
    static void pick(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Logx.e(T, "theme pick failed", t);
            CurriculumIo.toast(a, "打不开图片选择器：" + t);
        }
    }

    /** 把选到的图复制进模块私有目录（URI 权限会过期，必须落盘） */
    static boolean onPicked(Activity a, Uri uri) {
        try {
            String name = displayName(a, uri);
            String ext = name != null && name.contains(".")
                    ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "img";
            File dir = new File(a.getFilesDir(), "theme");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "bg." + ext);
            try (InputStream in = a.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) return false;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            resetBitmapCache();
            Prefs.setThemeBg(out.getAbsolutePath());
            Logx.i(T, "theme bg saved: " + out + " (" + out.length() + " bytes)");
            apply(a);
            return true;
        } catch (Throwable t) {
            Logx.e(T, "theme save failed", t);
            return false;
        }
    }

    static void clear(Activity a) {
        resetBitmapCache();
        Prefs.setThemeBg("");
        apply(a);
    }

    /** 应用当前主题（幂等：每次进入页面调用一次） */
    static void apply(Activity a) {
        if (a == null) return;
        try {
            ViewGroup content = a.findViewById(android.R.id.content);
            if (content == null) return;
            String path = Prefs.themeBg();
            View oldBg = content.findViewWithTag(TAG_BG);
            View oldScrim = content.findViewWithTag(TAG_SCRIM);
            if (path == null || path.isEmpty() || !new File(path).isFile()) {
                if (oldBg != null) content.removeView(oldBg);
                if (oldScrim != null) content.removeView(oldScrim);
                return;
            }
            Drawable d = loadDrawable(a, path);
            if (d == null) return;

            // 背景层（目标：content 的第 0 层，宿主所有内容之下）
            ImageView bg = new ImageView(a);
            bg.setTag(TAG_BG);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageDrawable(d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (oldBg != null) content.removeView(oldBg);
            content.addView(bg, 0, lp);
            bg.post(() -> bg.setTranslationY(-content.getTop()));

            // 遮罩层（背景之上、内容之下）
            int scrim = Prefs.themeScrim();
            View scrimV = new View(a);
            scrimV.setTag(TAG_SCRIM);
            scrimV.setBackgroundColor(Color.argb(scrim, 255, 255, 255));
            if (oldScrim != null) content.removeView(oldScrim);
            content.addView(scrimV, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            fadeHostBackgrounds(a);
            Logx.i(T, "theme applied: " + path + " scrim=" + scrim);
        } catch (Throwable t) {
            Logx.e(T, "theme apply failed", t);
        }
    }

    /** 把课表页里几块不透明背景放成半透明，否则背景图被白底整块盖住 */
    private static void fadeHostBackgrounds(Activity a) {
        for (String idName : new String[]{"root_view", "week_viewpager", "day_viewpager", "fl_tableView",
                "appBarLayout", "toolbar", "preview_toolbar", "sure_toolbar"}) {
            try {
                int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
                if (id == 0) continue;
                View v = a.findViewById(id);
                if (v == null) continue;
                Drawable bg = v.getBackground();
                if (bg instanceof ColorDrawable) {
                    int c = ((ColorDrawable) bg).getColor();
                    v.setBackgroundColor(Color.argb(110, Color.red(c), Color.green(c), Color.blue(c)));
                } else if (bg != null) {
                    bg.setAlpha(110);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static Drawable loadDrawable(Context ctx, String path) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder
                        .createSource(new File(path));
                return android.graphics.ImageDecoder.decodeDrawable(src);
            }
        } catch (Throwable t) {
            Logx.w(T, "ImageDecoder failed, fallback BitmapFactory: " + t);
        }
        try {
            Bitmap bm = BitmapFactory.decodeFile(path);
            if (bm == null) return null;
            return new BitmapDrawable(ctx.getResources(), bm);
        } catch (Throwable t) {
            Logx.e(T, "decode bg failed", t);
            return null;
        }
    }

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "bg.png" : p;
    }

    /** 供设置面板显示「当前有没有背景」 */
    static boolean hasBackground() {
        String p = Prefs.themeBg();
        return p != null && !p.isEmpty() && new File(p).isFile();
    }

    // ── 直接画进课表 View 的 canvas ──────────────────────────────────────────
    // 课表整张是 TableView 自绘（课程卡片、格子、文字都在 canvas 上），底层插 ImageView
    // 会被它的不透明绘制盖住。所以在它的 onDraw 之前把背景铺到 canvas 上。

    private static Bitmap bgBitmap;

    /** 供 TableView.onDraw 调用：把背景铺满整块画布。返回 false 表示没有背景 */
    static boolean drawBgOnCanvas(android.graphics.Canvas canvas, int w, int h) {
        try {
            String path = Prefs.themeBg();
            if (path == null || path.isEmpty()) return false;
            if (bgBitmap == null || bgBitmap.isRecycled()) {
                bgBitmap = BitmapFactory.decodeFile(path);
                if (bgBitmap == null) return false;
            }
            if (w <= 0 || h <= 0) return false;
            android.graphics.Rect dst = new android.graphics.Rect(0, 0, w, h);
            canvas.drawBitmap(bgBitmap, null, dst, null);
            int scrim = Prefs.themeScrim();
            if (scrim > 0) {
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(Color.argb(scrim, 255, 255, 255));
                canvas.drawRect(dst, p);
            }
            return true;
        } catch (Throwable t) {
            Logx.w(T, "drawBgOnCanvas failed: " + t);
            return false;
        }
    }

    static void resetBitmapCache() {
        if (bgBitmap != null && !bgBitmap.isRecycled()) bgBitmap.recycle();
        bgBitmap = null;
    }
    /** 主题设置面板：底部卡片 sheet（见 ThemeSheet），选完立刻生效 */
    static void showThemeMenu(Activity a) {
        if (a == null) return;
        ThemeSheet.show(a);
    }
    /**
     * 极简 GIF 播放：直接用 android.graphics.Movie 解码 + 自绘。
     * (Movie 在 API 28 后被标记废弃，但仍可用；静态图走 ImageView 就够。)
     */
    static final class GifView extends android.view.View {
        private android.graphics.Movie movie;
        private long startAt;
        private boolean running;

        GifView(Activity a) {
            super(a);
        }

        void setSource(java.io.File f) {
            try {
                movie = android.graphics.Movie.decodeFile(f.getAbsolutePath());
            } catch (Throwable e) {
                Logx.e(T, "gif decode failed", e);
            }
            if (movie == null) {
                Drawable d = Drawable.createFromPath(f.getAbsolutePath());
                if (d != null) setBackground(d);
                return;
            }
            startAt = System.currentTimeMillis();
            running = true;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (movie == null) return;
            int w = getWidth(), h = getHeight();
            int mw = movie.width(), mh = movie.height();
            if (mw <= 0 || mh <= 0 || w <= 0 || h <= 0) return;
            float scale = Math.max(w / (float) mw, h / (float) mh);
            float dx = (w - mw * scale) / 2f;
            float dy = (h - mh * scale) / 2f;
            int save = canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            int dur = movie.duration();
            if (dur <= 0) dur = 1000;
            movie.setTime((int) ((System.currentTimeMillis() - startAt) % dur));
            movie.draw(canvas, 0, 0);
            canvas.restoreToCount(save);
            if (running) postInvalidateOnAnimation();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (movie != null) {
                running = true;
                startAt = System.currentTimeMillis();
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            running = false;
            super.onDetachedFromWindow();
        }
    }
}