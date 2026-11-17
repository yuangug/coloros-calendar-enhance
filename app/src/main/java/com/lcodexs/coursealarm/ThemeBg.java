package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
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

    private static volatile ImageCache imageCache;
    private static final class ImageCache {
        final String key;
        final android.graphics.Bitmap bitmap;
        final float brightness;
        ImageCache(String key, android.graphics.Bitmap bitmap) {
            this.key = key; this.bitmap = bitmap;
            long sum = 0; int count = 0;
            for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 64)) {
                for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 64)) {
                    int color = bitmap.getPixel(x, y);
                    sum += Color.red(color) + Color.green(color) + Color.blue(color); count++;
                }
            }
            brightness = count == 0 ? .5f : sum / (count * 765f);
        }
    }

    private static String imageKey(File file) {
        return file == null ? "none" : file.getAbsolutePath() + ':' + file.lastModified() + ':' + file.length();
    }

    static String signature(Context c) {
        return ThemeStore.type(c) + ":" + ThemeStore.solidIndex(c) + ":" + ThemeStore.gradientIndex(c)
                + ":" + ThemeStore.scrim(c) + ":" + ThemeStore.imageAlpha(c) + ":" + ThemeStore.style(c)
                + ":" + c.getResources().getConfiguration().uiMode + ":" + imageKey(ThemeStore.imageFile(c));
    }

    private static ImageCache image(Context c) {
        File file = ThemeStore.imageFile(c);
        if (file == null) return null;
        String key = imageKey(file);
        ImageCache cached = imageCache;
        if (cached != null && key.equals(cached.key)) return cached;
        try {
            android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                cached = new ImageCache(key, bitmap);
                // Existing Drawables may still reference the old bitmap. Let GC retire it.
                imageCache = cached;
                return cached;
            }
        } catch (Exception error) { Logx.w(T, "theme image: " + error); }
        return null;
    }

    static void warm(Context c) {
        if (ThemeStore.type(c) == ThemeStore.TYPE_IMAGE) image(c);
    }

    /** Background luminance is cached per file, then combined with the current scrim. */
    static boolean isDarkBackground(Context c, int type) {
        float scrim = Math.max(0, Math.min(255, ThemeStore.scrim(c))) / 255f;
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (type == ThemeStore.TYPE_SOLID) {
            int idx = Math.max(0, Math.min(ThemeStore.solidIndex(c), ThemeStore.SOLIDS.length - 1));
            return veil(lum(ThemeStore.SOLIDS[idx]), scrim, night) < 0.55f;
        }
        if (type == ThemeStore.TYPE_GRADIENT) {
            int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(c), ThemeStore.GRADIENTS.length - 1));
            return veil((lum(ThemeStore.GRADIENTS[idx][0]) + lum(ThemeStore.GRADIENTS[idx][1])) / 2f,
                    scrim, night) < 0.55f;
        }
        if (type == ThemeStore.TYPE_IMAGE) {
            ImageCache cached = image(c);
            return cached != null && veil(cached.brightness, scrim, night) < .5f;
        }
        return false;
    }

    /**
     * 遮罩之后的实际亮度：白天叠白纱会把背景提亮、夜里叠黑纱会压暗。
     * 不把这一层算进去的话，「深」遮罩 + 深色图仍按图片原亮度选浅色字 —— 实测就是白字糊在
     * 半透明白底上看不清（真机截图见 logs\deploy-*.log 对应的验证记录）。
     */
    private static float veil(float lum, float scrim, boolean night) {
        return night ? lum * (1f - scrim) : lum * (1f - scrim) + scrim;
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
    static Drawable windowDrawable(View main) {
        return ThemeStore.type(main.getContext()) == ThemeStore.TYPE_NONE
                ? null : new ThemeWindowBackground(main, true);
    }

    /**
     * 图片模式下生成背景层；没有图返回 null。
     * 静态图解码失败时退回纯色底，不让页面空着。
     */
    static View layer(Context c) {
        animatedActive = false;
        File f = ThemeStore.imageFile(c);
        if (f == null) return null;
        String name = f.getName().toLowerCase();
        View v;
        boolean maybeAnim = name.endsWith(".gif") || name.endsWith(".webp");
        if (maybeAnim && Build.VERSION.SDK_INT >= 28) {
            // Android 9+：AnimatedImageDrawable + ImageView（标准用法，才会按系统时钟走帧）
            AnimView g = new AnimView(c);
            g.setSource(f);
            v = g;
        } else if (name.endsWith(".gif")) {
            GifView g = new GifView(c);          // Android 8 兜底（Movie）
            g.setSource(f);
            v = g;
        } else {
            ImageView iv = new ThemeWindowBackground.Image(c);
            try {
                ImageCache cached = image(c);
                android.graphics.Bitmap bm = cached == null ? null : cached.bitmap;
                if (bm != null) {
                    iv.setImageDrawable(new BitmapDrawable(c.getResources(), bm));
                }
            } catch (Throwable t) {
                Logx.w(T, "theme: 解码背景图失败 " + t);
            }
            v = iv;
        }
        v.setAlpha(Math.max(0f, Math.min(1f, ThemeStore.imageAlpha(c) / 100f)));
        return v;
    }

    // ── 动图背景（Android 9+） ────────────────────────────────────────────────

    /**
     * 动图背景层：{@link android.graphics.drawable.AnimatedImageDrawable} + ImageView。
     *
     * <p>不再用 {@code android.graphics.Movie}（Android 15+ 已退化成只剩第一帧，setTime 无效），
     * 也不再自己包一层 Drawable 手绘：那条路 drawable 的帧时钟不会走（实测 isRunning=true
     * 但画面不动）。标准 ImageView + setImageDrawable 才会拿到正常的动画回调。
     *
     * <p>图片矩阵由 ThemeWindowBackground.Image 统一到窗口坐标，状态栏与课表使用同一裁剪。
     */
    static final class AnimView extends ThemeWindowBackground.Image {
        private AnimatedImageDrawable anim;
        private int draws;

        AnimView(Context c) {
            super(c);
        }

        void setSource(File f) {
            try {
                Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f));
                setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) {
                    anim = (AnimatedImageDrawable) d;
                    anim.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    anim.start();
                    animatedActive = true;
                }
                Logx.i(T, "theme: 动图背景 " + f.getName() + " "
                        + (anim != null ? "animated" : "static")
                        + " " + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight());
            } catch (Throwable t) {
                Logx.e(T, "theme: 动图解码失败，退回静态解码", t);
                Drawable d = Drawable.createFromPath(f.getAbsolutePath());
                if (d != null) setImageDrawable(d);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (anim != null) { anim.start(); animatedActive = true; }
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) { anim.stop(); animatedActive = false; }
            super.onDetachedFromWindow();
        }

        @Override public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible);
            if (Build.VERSION.SDK_INT >= 28 && anim != null) {
                if (visible) anim.start(); else anim.stop();
                animatedActive = visible;
            }
        }

        @Override
        public void draw(Canvas canvas) {
            draws++;
            if (draws % 300 == 1) {
                Logx.i(T, "theme: 动图 draw #" + draws
                        + " running=" + (anim != null && anim.isRunning()));
            }
            super.draw(canvas);
        }
    }

    /** 背景层当前是不是「真在播」的动图 —— {@link Backdrop} 靠它决定要不要开逐帧采样 */
    static volatile boolean animatedActive;

    // ── 液态玻璃用的「模糊背景图」 ────────────────────────────────────────────

    private static android.graphics.Bitmap blurBmp;
    private static String blurKey;

    /**
     * 主题图的模糊版（长边压到 ~540px 再由画布放大 = 便宜的 box 级模糊）。
     * 液态玻璃折射的就是这一张：没有它，卡片里只能看到原图、边缘不会"弯"。
     * 按文件路径 + 修改时间缓存，换图自动失效。
     */
    static android.graphics.Bitmap blurredBackdrop(Context c) {
        File f = ThemeStore.imageFile(c);
        if (f == null) {
            blurBmp = null;
            blurKey = null;
            return null;
        }
        String key = f.getAbsolutePath() + ":" + f.lastModified();
        if (blurBmp != null && key.equals(blurKey)) return blurBmp;
        try {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int small = Math.max(1, Math.min(probe.outWidth, probe.outHeight));
            int sample = Math.max(1, small / 540);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            blurBmp = bm;
            blurKey = key;
            Logx.i(T, "液态玻璃背景：模糊图 " + (bm == null ? "null"
                    : bm.getWidth() + "x" + bm.getHeight()) + " sample=" + sample);
            return bm;
        } catch (Throwable t) {
            Logx.w(T, "模糊背景解码失败 " + t);
            return null;
        }
    }

    // ── 液态玻璃的「真实背景」位图（把主题背景 + 遮罩自己渲染出来） ────────────

    private static android.graphics.Bitmap bd;
    private static String bdKey;

    /**
     * 把当前主题背景（纯色 / 渐变 / 图片 + 遮罩）渲染成一张页面形状的位图（1/3 分辨率，放大即模糊）。
     *
     * 为什么不是 PixelCopy：PixelCopy 抓的是**窗口合成结果**，课程卡本身就在同一个窗口里，
     * 抓回来会把自己也画进纹理（自反馈/递归玻璃），而且它是异步回调，onDraw 里同步拿不到。
     * 背景层是我们自己铺的（ColorDrawable / GradientDrawable / 图片），直接渲染既同步又干净，
     * 还能覆盖「纯色 / 渐变」这种没有位图的主题。
     */
    static android.graphics.Bitmap backdrop(Context c, int w, int h) {
        if (c == null || w <= 0 || h <= 0) return null;
        int type = ThemeStore.type(c);
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        File img = ThemeStore.imageFile(c);
        String key = type + ":" + ThemeStore.solidIndex(c) + ":" + ThemeStore.gradientIndex(c)
                + ":" + (img == null ? "-" : img.lastModified())
                + ":" + ThemeStore.scrim(c) + ":" + night + ":" + w + "x" + h;
        android.graphics.Bitmap cached = bd;
        if (cached != null && key.equals(bdKey) && !cached.isRecycled()) return cached;

        try {
            int bw = Math.max(1, w / 2);
            int bh = Math.max(1, h / 2);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            cv.scale(bw / (float) w, bh / (float) h);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.RectF full = new android.graphics.RectF(0, 0, w, h);

            p.setColor(night ? 0xFF101216 : 0xFFF7F8FA);
            cv.drawRect(full, p);

            if (type == ThemeStore.TYPE_SOLID) {
                int idx = Math.max(0, Math.min(ThemeStore.solidIndex(c), ThemeStore.SOLIDS.length - 1));
                p.setColor(ThemeStore.SOLIDS[idx]);
                cv.drawRect(full, p);
            } else if (type == ThemeStore.TYPE_GRADIENT) {
                int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(c), ThemeStore.GRADIENTS.length - 1));
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                        ThemeStore.GRADIENTS[idx][0], ThemeStore.GRADIENTS[idx][1],
                        android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(full, p);
                p.setShader(null);
            } else if (type == ThemeStore.TYPE_IMAGE && img != null) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = Math.max(1, Math.min(8, Math.max(img.length() > 1_500_000 ? 4 : 2, 1)));
                android.graphics.Bitmap src = BitmapFactory.decodeFile(img.getAbsolutePath(), o);
                if (src != null) {
                    float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setScale(s, s);
                    m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
                    android.graphics.Paint bp = new android.graphics.Paint(
                            android.graphics.Paint.FILTER_BITMAP_FLAG);
                    // vibrancy()：提饱和 + 轻微提对比（Backdrop 的 vibrancy 效果）
                    android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                    cm.setSaturation(1.22f);
                    android.graphics.ColorMatrix contrast = new android.graphics.ColorMatrix(new float[]{
                            1.06f, 0, 0, 0, -6f,
                            0, 1.06f, 0, 0, -6f,
                            0, 0, 1.06f, 0, -6f,
                            0, 0, 0, 1, 0});
                    cm.postConcat(contrast);
                    bp.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                    cv.drawBitmap(src, m, bp);
                    src.recycle();
                }
            }

            int scrim = ThemeStore.scrim(c);
            if (type != ThemeStore.TYPE_NONE && scrim > 0) {
                p.setShader(null);
                p.setColor(night ? (scrim << 24) : ((scrim << 24) | 0x00FFFFFF));
                cv.drawRect(full, p);
            }

            boxBlur(out, Math.max(1, Math.round(4f * c.getResources().getDisplayMetrics().density / 2f)));
            bd = out;
            bdKey = key;
            Logx.i(T, "液态玻璃背景位图 " + bw + "x" + bh + " type=" + type + " scrim=" + scrim);
            return out;
        } catch (Throwable t) {
            Logx.w(T, "背景位图渲染失败 " + t);
            return null;
        }
    }
    /** 简易 box blur（两次水平+垂直），半径按位图缩放换算；只做一次，随主题缓存 */
    private static void boxBlur(android.graphics.Bitmap bmp, int radius) {
        if (radius <= 0) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] src = new int[w * h];
        int[] dst = new int[w * h];
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            // 水平
            for (int y = 0; y < h; y++) {
                int row = y * w;
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int x = -radius; x <= radius; x++) {
                    int c = src[row + Math.max(0, Math.min(w - 1, x))];
                    a += (c >>> 24) & 0xFF; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
                    count++;
                }
                for (int x = 0; x < w; x++) {
                    dst[row + x] = ((int) (a / count) << 24) | ((int) (r / count) << 16)
                            | ((int) (g / count) << 8) | (int) (b / count);
                    int out = src[row + Math.max(0, x - radius)];
                    int in = src[row + Math.min(w - 1, x + radius + 1)];
                    a += ((in >>> 24) & 0xFF) - ((out >>> 24) & 0xFF);
                    r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                    g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                    b += (in & 0xFF) - (out & 0xFF);
                }
            }
            // 垂直
            for (int x = 0; x < w; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int y = -radius; y <= radius; y++) {
                    int c = dst[Math.max(0, Math.min(h - 1, y)) * w + x];
                    a += (c >>> 24) & 0xFF; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
                    count++;
                }
                for (int y = 0; y < h; y++) {
                    src[y * w + x] = ((int) (a / count) << 24) | ((int) (r / count) << 16)
                            | ((int) (g / count) << 8) | (int) (b / count);
                    int out = dst[Math.max(0, y - radius) * w + x];
                    int in = dst[Math.min(h - 1, y + radius + 1) * w + x];
                    a += ((in >>> 24) & 0xFF) - ((out >>> 24) & 0xFF);
                    r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                    g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                    b += (in & 0xFF) - (out & 0xFF);
                }
            }
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }
    /** 极简 GIF 播放：Movie 解码 + 自绘，铺满父容器（居中裁剪） */    static final class GifView extends View {
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










