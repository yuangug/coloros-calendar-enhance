package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.util.Locale;

/**
 * 实时背景采样（backdrop）。
 *
 * <p>两块玻璃的材质都必须「采到下面真实画着的东西」，否则只是自己糊一层膜：
 *
 * <ul>
 *   <li><b>液态玻璃</b>要的是<b>未模糊</b>的背景——折射靠把背景按 SDF 法线位移重采样，
 *       先糊一遍就再也看不出弯折；</li>
 *   <li><b>凝光视效</b>要的是<b>模糊后</b>的背景——它是磨砂散射，模糊在 shader 里用
 *       9 taps 实时做（GPU），不预先烘进位图。</li>
 * </ul>
 *
 * <p>采样源不是「自己再画一遍主题」（那是死图，动图不动、遮罩/图片比例还容易对不上），
 * 而是页面根上<b>真实挂着的那几个 View</b>：{@link HostTablePage#TAG_BG} 背景层、
 * {@link HostTablePage#TAG_SCRIM} 遮罩层，加 root 自己的底色。把它们 {@code draw(Canvas)}
 * 进一张 1/2 分辨率的位图（双线性放大天然给到 2px 级柔化，又保住折射细节）。
 *
 * <p><b>实时</b>：GIF 背景时每个 vsync 让宿主课表重画一次（{@link #pump}），位图按 30fps
 * 重新抓那一帧——玻璃里看到的就是正在播放的那一帧；静态背景只在主题/尺寸变化时重建。
 * 位图对象复用、shader 只在尺寸变化时重建，逐帧只是重画内容，不上传新纹理对象。
 */
final class Backdrop {
    private static final String T = CourseAlarmEntry.TAG;

    /** 采样分辨率：静态背景 1/2；动图 1/3（每帧都要重传纹理，降一半像素量就不闪） */
    private static final int DIV = 2;
    private static final int DIV_LIVE = 3;
    /** 动图的重抓间隔：20fps —— 60fps 每帧重传 3MB 纹理，会让玻璃一闪一闪 */
    private static final long LIVE_INTERVAL_MS = 50L;

    private static Bitmap bmp;          // 前台（被 shader 采样）
    private static Bitmap back;         // 后台（正在重绘）
    private static Canvas bmpCanvas;
    private static BitmapShader shader;
    /** 读画布矩阵用（复用，避免每帧分配） */
    private static final Matrix M0 = new Matrix();
    private static final float[] MV = new float[9];
    private static String cacheKey;
    private static int bw, bh;
    /** 页面坐标 → 位图坐标 */
    private static float scale = 0.5f;
    /** 课表画布坐标 → 页面坐标 */
    private static float offX, offY;
    private static boolean live;
    private static long lastBuild;

    static int builds;
    static int frames;

    private Backdrop() {
    }

    // ── 每帧入口 ──────────────────────────────────────────────────────────────

    /**
     * 课表每画一张卡片都会调用一次：算映射、按需重建位图、决定要不要开帧泵。
     *
     * <p><b>为什么还要一个「滑动看门狗」</b>（{@link #watch}）：宿主课表上下滑动时是父容器
     * 移动子 View 的 RenderNode，**子 View 的显示列表不会重录** → {@code drawCourse} 根本不再
     * 被调用 → 映射停在滑动前的位置，玻璃里就是「滑动之前的背景」（割裂）。
     * 所以除了在这里算映射，还要在每一帧检查位置变了没有，变了就把课表 invalidate 一次，
     * 逼它重录、重新跑这里。
     */
    static void prepare(Context c, View table, Canvas cv) {
        try {
            View root = HostTablePage.rootView();
            if (c == null || root == null || table == null) return;
            View anchor = anchorOf(root);
            int w = anchor.getWidth();
            int h = anchor.getHeight();
            if (w <= 0 || h <= 0) return;

            int[] tl = new int[2];
            int[] aw = new int[2];
            table.getLocationInWindow(tl);
            anchor.getLocationInWindow(aw);
            offX = tl[0] - aw[0];
            offY = tl[1] - aw[1];
            // ViewPager2 会预挂相邻周次的课表：离屏那一页也会跑 drawCourse。
            // 它如果抢到「看门狗/帧泵」的目标，滑动时我们在给离屏页做无效重绘，
            // 可见页反而停在旧映射上 —— 所以只认真正在屏内的那一张。
            boolean onScreen = Math.abs(offX) < w * 0.75f;
            if (onScreen) {
                lastTable = table;
                lastRoot = root;
            }
            frames++;
            if (frames % 120 == 0) {
                Logx.i(T, "backdrop: 映射 off=" + Math.round(offX) + "," + Math.round(offY)
                        + " (table=" + tl[0] + "," + tl[1] + " anchor=" + aw[0] + "," + aw[1] + ")");
            }

            live = isLive(c);
            long now = SystemClock.uptimeMillis();
            String key = keyOf(c, w, h);
            boolean need = bmp == null || bmpCanvas == null
                    || !key.equals(cacheKey)
                    || bmp.isRecycled()
                    || (live && now - lastBuild >= LIVE_INTERVAL_MS);
            if (need) rebuild(c, root, w, h, key);

            if (live && onScreen) pump(table);
            else if (!live) stop();
            if (onScreen) watch(table);
        } catch (Throwable e) {
            Logx.w(T, "backdrop: prepare 失败 " + e);
        }
    }

    // ── 滑动看门狗：位置一变就让课表重录一次 ─────────────────────────────────

    private static View lastTable;
    private static View lastRoot;
    private static float lastWatchedX = Float.NaN;
    private static float lastWatchedY = Float.NaN;

    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            View t = lastTable;
            View root = lastRoot;
            if (t == null || root == null || !watching) return;
            if (!t.isShown()) {
                watching = false;
                lastTable = null;
                return;
            }
            try {
                View anchor = anchorOf(root);
                int[] tl = new int[2];
                int[] aw = new int[2];
                t.getLocationInWindow(tl);
                anchor.getLocationInWindow(aw);
                float x = tl[0] - aw[0];
                float y = tl[1] - aw[1];
                if (Float.isNaN(lastWatchedX) || Math.abs(x - lastWatchedX) > 0.5f
                        || Math.abs(y - lastWatchedY) > 0.5f) {
                    lastWatchedX = x;
                    lastWatchedY = y;
                    t.invalidate();          // 位置变了：逼它重录，prepare 会带着新映射再跑一次
                }
            } catch (Throwable ignored) {
            }
            t.postOnAnimation(this);
        }
    };

    private static volatile boolean watching;

    private static void watch(View table) {
        if (watching || table == null) return;
        watching = true;
        lastWatchedX = Float.NaN;
        lastWatchedY = Float.NaN;
        table.postOnAnimation(WATCH);
    }

    /** 采样锚点：背景层 → 遮罩层 → 页面根（都没有就只能自己兜底） */
    private static View anchorOf(View root) {
        View v = root.findViewWithTag(HostTablePage.TAG_BG);
        if (v != null && v.getWidth() > 0) return v;
        v = root.findViewWithTag(HostTablePage.TAG_SCRIM);
        if (v != null && v.getWidth() > 0) return v;
        return root;
    }

    private static String keyOf(Context c, int w, int h) {
        File img = ThemeStore.imageFile(c);
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return ThemeStore.type(c) + "|" + ThemeStore.solidIndex(c) + "|" + ThemeStore.gradientIndex(c)
                + "|" + (img == null ? "-" : img.lastModified()) + "|" + ThemeStore.scrim(c)
                + "|" + night + "|" + w + "x" + h;
    }

    private static boolean isLive(Context c) {
        // 只有「背景层真的是在播的动图」才需要逐帧重抓；静态 webp / png 走缓存
        if (ThemeBg.animatedActive) return true;
        File f = ThemeStore.imageFile(c);
        if (f == null) return false;
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".gif") && !n.endsWith(".webp")) return false;
        // Android 8 走 Movie 兜底，也算动图
        return Build.VERSION.SDK_INT < 28;
    }

    // ── 重建位图 ──────────────────────────────────────────────────────────────

    private static void rebuild(Context c, View root, int w, int h, String key) {
        int div = live ? DIV_LIVE : DIV;
        int nw = Math.max(1, w / div);
        int nh = Math.max(1, h / div);
        if (back == null || back.isRecycled() || back.getWidth() != nw || back.getHeight() != nh) {
            back = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);

        }
        // Rebind after every front/back swap; retired textures are left to GC, never recycled while referenced.
        if (bmpCanvas == null) bmpCanvas = new Canvas(back); else bmpCanvas.setBitmap(back);
        back.eraseColor(android.graphics.Color.TRANSPARENT);
        float s = nw / (float) w;

        Canvas cv = bmpCanvas;
        int save = cv.save();
        try {
            cv.scale(s, s);
            Drawable d = root.getBackground();
            if (d != null) {
                d.setBounds(0, 0, w, h);
                d.draw(cv);
            }
            for (String tag : new String[]{HostTablePage.TAG_BG, HostTablePage.TAG_SCRIM}) {
                View v = root.findViewWithTag(tag);
                if (v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0) {
                    v.draw(cv);                  // gif 走 ImageView/AnimatedImageDrawable，画的就是当前帧
                }
            }
        } finally {
            cv.restoreToCount(save);
        }

        // 双缓冲交换：**绝不**改写正在被 shader 采样的那张位图。
        // 单缓冲时「eraseColor + 重画」中间的半张图会被上一帧的 GPU 采样到 —— 表现就是玻璃一闪。
        Bitmap old = bmp;
        bmp = back;
        back = old;
        bw = nw;
        bh = nh;
        scale = s;
        shader = null;                       // 换了位图，shader 必须重建
        cacheKey = key;
        lastBuild = SystemClock.uptimeMillis();
        builds++;
        if (builds == 1 || !live) {
            Logx.i(T, "backdrop: 采样 " + nw + "x" + nh + " scale="
                    + String.format(Locale.US, "%.3f", scale)
                    + " off=" + offX + "," + offY + (live ? " live(gif)" : " static"));
        }
    }

    // ── GIF：让宿主课表逐帧重画 ───────────────────────────────────────────────

    private static View tickView;
    private static volatile boolean ticking;

    private static final Runnable FRAME = new Runnable() {
        @Override
        public void run() {
            View v = tickView;
            if (!ticking || v == null) return;
            if (!v.isShown()) {
                ticking = false;
                tickView = null;
                return;
            }
            v.invalidate();                      // 触发 onDraw → drawCourse → prepare → 重抓
            v.postOnAnimation(this);
        }
    };

    private static void pump(View v) {
        if (ticking || v == null) return;
        ticking = true;
        tickView = v;
        v.postOnAnimation(FRAME);
        Logx.i(T, "backdrop: 开帧泵（gif 背景，逐帧重采样）");
    }

    static void stop() {
        if (ticking) Logx.i(T, "backdrop: 收帧泵");
        ticking = false;
        tickView = null;
    }

    // ── 给 shader 用 ──────────────────────────────────────────────────────────

    /** 当前采样纹理；没有背景（TYPE_NONE）也返回非空——底色在位图里 */
    static BitmapShader shader() {
        Bitmap b = bmp;
        if (b == null || b.isRecycled()) return null;
        if (shader == null) {
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= 27) s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            s.setLocalMatrix(null);
            shader = s;
        }
        return shader;
    }

    /** 页面坐标 → 位图坐标的缩放（shader 里的 mapScale） */
    static float mapScale() {
        return scale;
    }

    static float offX() {
        return offX;
    }

    static float offY() {
        return offY;
    }

    static boolean live() {
        return live;
    }

    /** 诊断用：一次采样重建的耗时/次数 */
    static String describe() {
        return "backdrop " + bw + "x" + bh + " builds=" + builds + " frames=" + frames
                + " live=" + live;
    }

    private static final Paint DUMMY = new Paint();   // 占位，避免误用
}
