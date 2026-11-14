package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

import java.io.File;

/**
 * 课表页主题的背景层：纯色 / 渐变走页面底色，图片（png / jpg / webp / gif 动图）走这一层。
 *
 * 页面本身（{@link HostTablePage}）只负责把这一层插到最底下；宿主自己的 TableView 是透明画布，
 * 所以背景就在格子与课程卡片之下，不需要改宿主任何绘制代码 —— 课表外观保持不变。
 *
 * gif 用 {@link android.graphics.Movie} 自绘（API 28 起标记废弃但仍可用），静态图走 ImageView。
 */
final class ThemeBg {
    private static final String T = CourseAlarmEntry.TAG;

    /** 选背景图（系统文件选择器，只收图片；gif 也在 image/* 里） */
    static final int REQ_PICK = 0xCA20;

    /**
     * 当前是不是「图片主题」。
     *
     * 宿主课表的空格子是用 R.color.empty_course_bg 实心画出来的（TableView#drawEmptyCourse 每格
     * new Paint + drawRoundRect），整块网格会把这层背景图完全挡住。所以图片主题生效期间，
     * 我们把那次取色换成半透明白（见 Hooks#hookEmptyCellColor），格子就从「实心」变「磨砂」，
     * 背景图透出来，课程卡片和文字仍由宿主原样绘制 —— 没有改动任何宿主绘制逻辑。
     */
    static volatile boolean imageActive = false;

    private ThemeBg() {
    }

    static void pick(Activity a) {
        if (a == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivityForResult(i, REQ_PICK);
            Logx.i(T, "theme: 打开图片选择器");
        } catch (Throwable t) {
            Logx.e(T, "theme pick failed", t);
            CurriculumIo.toast(a, "打不开图片选择器：" + t);
        }
    }

    /** 选到的图复制进模块私有目录（URI 授权会过期，必须落盘） */
    static boolean onPicked(Activity a, Uri uri) {
        boolean ok = ThemeStore.importImage(a, uri);
        if (ok) apply(a);
        return ok;
    }

    static void clear(Activity a) {
        ThemeStore.setType(a, ThemeStore.TYPE_NONE);
        apply(a);
    }

    /** 主题改了：让当前课表页立刻重铺背景 */
    static void apply(Activity a) {
        if (a == null) return;
        HostTablePage.applyTheme();
        BarMode.refreshBackdrop(a);
        String f = ThemeStore.imageFile(a) == null ? "无" : ThemeStore.imageFile(a).getName();
        Logx.i(T, "theme applied type=" + ThemeStore.type(a) + " image=" + f
                + " scrim=" + ThemeStore.scrim(a));
    }

    /**
     * 图片主题开关：同时把宿主课表顶栏底色（TOP_TITLE_BG）调成透明，
     * 让顶部「11周 周一 11/9 …」那条也不挡背景图。
     */
    /**
     * 把课表的绘制色板按主题改掉（宿主 TimeTableDimensionRes 是静态配置，setter 齐全）：
     *
     *   TOP_TITLE_BG            → 透明（顶部「11周 周一 11/9…」那条白底消失，露出背景）
     *   PRIMARY_COLOR           → 主文字（星期名、当前天、周次、时间轴）
     *   SUB_TITLE_COLOR         → 次文字（日期、节次起止时间）
     *   TEXT_COLOR_NOT_THIS_WEEK→ 非当天/非本周的灰字
     *
     * 颜色按「背景亮度」选深/浅：纯色、渐变算亮度；图片先降采样算平均亮度。
     * 宿主的 TableView 构造时会调 loadRes(context) 把这块色板重刷一遍，
     * 所以 Hooks 里也 hook 了 TimeTableDimensionRes#loadRes，每次重刷后我们再盖一次。
     */
    static void applyTokens(Context c) {
        try {
            Object dim = dimInstance(c);
            if (dim == null) return;
            int type = ThemeStore.type(c);
            if (type == ThemeStore.TYPE_NONE) {
                restoreTokens(dim);
                return;
            }
            captureTokens(dim);
            boolean dark = isDarkBackground(c, type);
            int ink = dark ? 0xFFF2F3F5 : 0xFF1A1A1A;
            int sub = dark ? 0xCCF2F3F5 : 0x99000000;
            int weak = dark ? 0x8AFFFFFF : 0x8A000000;
            Ref.callName(dim, "setTOP_TITLE_BG", 0x00000000);
            Ref.callName(dim, "setPRIMARY_COLOR", ink);
            Ref.callName(dim, "setSUB_TITLE_COLOR", sub);
            Ref.callName(dim, "setTEXT_COLOR_NOT_THIS_WEEK", weak);
            Logx.i(T, "课表色板 → " + (dark ? "浅字" : "深字") + "（背景亮度判定 type=" + type + "）");
        } catch (Throwable t) {
            Logx.w(T, "applyTokens 失败 " + t);
        }
    }

    private static Integer oTop, oPrimary, oSub, oWeak;

    private static void captureTokens(Object dim) {
        if (oTop != null) return;
        oTop = asInt(Ref.callName(dim, "getTOP_TITLE_BG"));
        oPrimary = asInt(Ref.callName(dim, "getPRIMARY_COLOR"));
        oSub = asInt(Ref.callName(dim, "getSUB_TITLE_COLOR"));
        oWeak = asInt(Ref.callName(dim, "getTEXT_COLOR_NOT_THIS_WEEK"));
    }

    private static void restoreTokens(Object dim) {
        if (oTop == null) return;
        Ref.callName(dim, "setTOP_TITLE_BG", oTop);
        Ref.callName(dim, "setPRIMARY_COLOR", oPrimary);
        Ref.callName(dim, "setSUB_TITLE_COLOR", oSub);
        Ref.callName(dim, "setTEXT_COLOR_NOT_THIS_WEEK", oWeak);
        oTop = null;
        oPrimary = null;
        oSub = null;
        oWeak = null;
        Logx.i(T, "课表色板已还原");
    }

    private static Integer asInt(Object o) {
        return o instanceof Integer ? (Integer) o : 0;
    }

    /** TimeTableDimensionRes.INSTANCE（静态字段，必须从 Class 上取） */
    static Object dimInstance(Context c) {
        try {
            Class<?> dim = Ref.cls(c.getClassLoader(),
                    "com.android.calendar.card.schooltimetable.view.TimeTableDimensionRes");
            if (dim == null) return null;
            return dim.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 背景够不够暗：纯色/渐变算亮度，图片降采样算平均亮度 */
    static boolean isDarkBackground(Context c, int type) {
        if (type == ThemeStore.TYPE_SOLID) {
            int idx = Math.max(0, Math.min(ThemeStore.solidIndex(c), ThemeStore.SOLIDS.length - 1));
            return lum(ThemeStore.SOLIDS[idx]) < 0.55f;
        }
        if (type == ThemeStore.TYPE_GRADIENT) {
            int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(c), ThemeStore.GRADIENTS.length - 1));
            return (lum(ThemeStore.GRADIENTS[idx][0]) + lum(ThemeStore.GRADIENTS[idx][1])) / 2f < 0.55f;
        }
        if (type == ThemeStore.TYPE_IMAGE) {
            File f = ThemeStore.imageFile(c);
            if (f == null) return false;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 16;
                android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (bm == null) return false;
                long sum = 0;
                int n = 0;
                for (int y = 0; y < bm.getHeight(); y += 2) {
                    for (int x = 0; x < bm.getWidth(); x += 2) {
                        sum += android.graphics.Color.red(bm.getPixel(x, y))
                                + android.graphics.Color.green(bm.getPixel(x, y))
                                + android.graphics.Color.blue(bm.getPixel(x, y));
                        n++;
                    }
                }
                bm.recycle();
                return n > 0 && (sum / (float) (n * 3 * 255)) < 0.5f;
            } catch (Throwable t) {
                Logx.w(T, "图片亮度判定失败 " + t);
            }
        }
        return false;
    }

    private static float lum(int col) {
        return (0.299f * android.graphics.Color.red(col)
                + 0.587f * android.graphics.Color.green(col)
                + 0.114f * android.graphics.Color.blue(col)) / 255f;
    }

    /** 兼容旧调用：等价于 applyTokens */
    static void setImageActive(Context c, boolean on) {
        applyTokens(c);
    }

    private static Integer origTitleBg;

    /**
     * 整页背衬（给「顶部留白条」用）：把主题铺成 Drawable，套到主页的 ll_main 上，
     * 这样 appbar 收起后露出的那条、以及状态栏下面那条都不会再是死白。
     * 没有主题时返回 null（调用方负责清空）。
     */
    static android.graphics.drawable.Drawable windowDrawable(Context c) {
        int type = ThemeStore.type(c);
        if (type == ThemeStore.TYPE_SOLID) {
            int idx = Math.max(0, Math.min(ThemeStore.solidIndex(c), ThemeStore.SOLIDS.length - 1));
            return new android.graphics.drawable.ColorDrawable(ThemeStore.SOLIDS[idx]);
        }
        if (type == ThemeStore.TYPE_GRADIENT) {
            int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(c), ThemeStore.GRADIENTS.length - 1));
            return new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    ThemeStore.GRADIENTS[idx]);
        }
        if (type == ThemeStore.TYPE_IMAGE) {
            File f = ThemeStore.imageFile(c);
            if (f == null) return null;
            try {
                android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm == null) return null;
                android.graphics.drawable.BitmapDrawable d =
                        new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
                d.setGravity(android.view.Gravity.FILL);
                return d;
            } catch (Throwable t) {
                Logx.w(T, "windowDrawable 解码失败 " + t);
            }
        }
        return null;
    }

    /**
     * 图片模式下生成背景层；没有图返回 null。
     * 静态图解码失败时退回纯色底，不让页面空着。
     */
    static View layer(Context c) {
        File f = ThemeStore.imageFile(c);
        if (f == null) return null;
        String name = f.getName().toLowerCase();
        View v;
        if (name.endsWith(".gif")) {
            GifView g = new GifView(c);
            g.setSource(f);
            v = g;
        } else {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) {
                    iv.setImageDrawable(new BitmapDrawable(c.getResources(), bm));
                }
            } catch (Throwable t) {
                Logx.w(T, "theme: 解码背景图失败 " + t);
            }
            v = iv;
        }
        v.setAlpha(Math.max(0, Math.min(255,
                Math.round(255f * ThemeStore.imageAlpha(c) / 100f))));
        return v;
    }

    /** 极简 GIF 播放：Movie 解码 + 自绘，铺满父容器（居中裁剪） */
    static final class GifView extends View {
        private android.graphics.Movie movie;
        private long startAt;
        private boolean running;

        GifView(Context c) {
            super(c);
        }

        void setSource(File f) {
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




