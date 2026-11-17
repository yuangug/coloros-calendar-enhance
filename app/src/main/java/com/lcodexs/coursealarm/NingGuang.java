package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 课表「凝光」材质：课程卡片、空格子的 ColorOS 16/17 凝光视效（轮廓光 + 顶部凝光 + 底部折射）。
 *
 * <pre>
 *  ColorOS 17（本机 PLR110 / Android 17 / ColorOS V17.0.0）
 *    → 直接吃宿主自己那套凝光资源（COUI 17 的 caustic token，名字稳定、按名字取，不用 R.id）：
 *        color/coui_caustic_color_1   #2effffff   侧边弱光   → rimSide
 *        color/coui_caustic_color_2   #42ffffff   顶部凝光   → sheen
 *        color/coui_caustic_color_3   #ccffffff   近线亮边   → rimNear
 *        color/coui_caustic_color_4   #990066ff   远线折射   → rimFar（压到 1/3 alpha 用）
 *        dimen/coui_caustic_layout_sdf_level3      轮廓光扩散半径的依据
 *      配色取到就说明是 COUI 17，走「系统资源」这条；几何比例照宿主
 *      InnerShadowStyle 的 D/E 两套预设（strokeRatio .68 / near .8 / far .4），
 *      也就是 ColorOS 17 原生卡片用的那组数。
 *
 *  ColorOS 16（或宿主资源没这两组 token）
 *    → 退回 COUI-Kit（github.com/ItosEO/COUI-Kit）的兜底参数：
 *      轮廓线 1dp、近线 alpha 0.7、远线 0.6、外部辉光 120*0.34*density、发光偏移 20*0.34*density。
 *      这两组数来自它的 CouiContourPresets（shadowEdgeArray / shadowGlowArray）。
 * </pre>
 *
 * 只在 TableView 的绘制线程（主线程）里跑：Paint/Path/RectF 全部复用静态实例，不做分配。
 */
final class NingGuang {
    private static final String T = CourseAlarmEntry.TAG;

    /** 凝光扩散半径上限：再大就糊成一团（单位 dp） */
    private static final float GLOW_MAX_DP = 18f;

    private NingGuang() {
    }

    // ── token ────────────────────────────────────────────────────────────────

    static final class Tokens {
        int sheen = 0x42FFFFFF;        // 顶部凝光
        int rimNear = 0xCCFFFFFF;      // 上沿近线
        int rimFar = 0x330066FF;       // 下沿远线（折射）
        int rimSide = 0x2EFFFFFF;      // 侧边弱光
        float rimDp = 1.1f;            // 轮廓线宽
        float gloss = 0.52f;           // 顶部高光覆盖比例
        /** true = ColorOS 17 宿主 caustic 资源；false = COUI-Kit 兜底 */
        boolean system;
        String source = "builtin";
    }

    private static volatile Tokens tokens;
    private static volatile int tokensKey = -1;

    static Tokens tokens(Context c) {
        if (c == null) return tokens == null ? new Tokens() : tokens;
        int key = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                | (c.getResources().getConfiguration().densityDpi << 2)
                | (ThemeStore.forceLegacy(c) ? (1 << 20) : 0);
        Tokens t = tokens;
        if (t != null && tokensKey == key) return t;
        synchronized (NingGuang.class) {
            if (tokens != null && tokensKey == key) return tokens;
            Tokens nt = new Tokens();
            try {
                Resources r = c.getResources();
                String pkg = c.getPackageName();
                int c1 = color(r, pkg, "coui_caustic_color_1");
                int c2 = color(r, pkg, "coui_caustic_color_2");
                int c3 = color(r, pkg, "coui_caustic_color_3");
                int c4 = color(r, pkg, "coui_caustic_color_4");
                if (c3 != 0 && !ThemeStore.forceLegacy(c)) {
                    nt.system = true;
                    nt.source = "coui_caustic(ColorOS 17)";
                    if (c2 != 0) nt.sheen = c2;
                    if (c1 != 0) nt.rimSide = c1;
                    nt.rimNear = c3;
                    if (c4 != 0) nt.rimFar = withAlpha(c4, 0x2C);
                    float sdf = dimen(r, pkg, "coui_caustic_layout_sdf_level3", 0f);
                    if (sdf > 0f) nt.rimDp = clamp(sdf * 0.11f, 1f, 2f);
                } else {
                    // COUI-Kit 兜底（ColorOS 16）：1dp 线、近 .7 / 远 .6 的轮廓光
                    nt.system = false;
                    nt.source = "COUI-Kit(legacy)";
                    nt.rimDp = 1f;
                    nt.sheen = 0x33FFFFFF;
                    nt.rimNear = 0xB3FFFFFF;
                    nt.rimFar = 0x59FFFFFF;
                    nt.rimSide = 0x24FFFFFF;
                }
            } catch (Throwable e) {
                Logx.w(T, "凝光 token 读取失败，用内置参数: " + e);
            }
            tokens = nt;
            tokensKey = key;
            Logx.i(T, "凝光 token = " + nt.source + " rimDp=" + nt.rimDp);
            return nt;
        }
    }

    static boolean night(Context c) {
        return c != null && (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // ── 课程卡片 ──────────────────────────────────────────────────────────────

    /**
     * 画一张课程卡片的凝光：调用点必须落在「卡片底色已画、课程文字还没画」之间
     * （Hooks 里挂在 TableView#drawCourseText 之前），这样光在字下面、底色上面。
     */
    static void card(Canvas cv, Context c, float l, float t, float r, float b, float rad) {
        if (cv == null || c == null) return;
        int style = ThemeStore.style(c);
        if (style == ThemeStore.STYLE_CLASSIC) return;
        if (style == ThemeStore.STYLE_LIQUID) {
            // 液态玻璃是另一套材质（粗玻璃边 + 镜面高光 + 外阴影），整套交给 LiquidGlass
            LiquidGlass.card(cv, c, l, t, r, b, rad);
            return;
        }
        float w = r - l;
        float h = b - t;
        if (w < 6f || h < 6f) return;
        Tokens tk = tokens(c);
        float k = night(c) ? 0.72f : 1f;
        float radius = Math.min(rad, Math.min(w, h) * 0.5f);

        // ── 主材质：AGSL 一条链（磨砂 + couiMaterial 混色 + 内晕 + 轮廓光） ──────
        boolean gpu = NingGuangShader.draw(cv, c, tk, l, t, r, b, radius, 1f, true);
        if (gpu) {
            NingGuangShader.sheen(cv, c, tk, l, t, r, b, radius, 1f);
            // 下 1/3 课程色收口：这门课的颜色（识别用，不参与材质）
            RECT.set(l, t, r, b);
            PATH.reset();
            PATH.addRoundRect(RECT, radius, radius, Path.Direction.CW);
            int save = cv.save();
            try {
                cv.clipPath(PATH);
                int base = lastCourseColor;
                float startY = t + h * 0.66f;
                P.reset();
                P.setStyle(Paint.Style.FILL);
                P.setShader(new LinearGradient(0f, startY, 0f, b, new int[]{
                        0x00000000, withAlpha(base, 0x4A), withAlpha(base, 0xC8)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRect(l, startY, r, b, P);
                P.setShader(null);
            } finally {
                cv.restoreToCount(save);
            }
            halo(cv, c, tk, l, t, r, b, radius, k);
            return;
        }

        // ── 兜底（SDK < 33）：几何近似，保持旧观感 ─────────────────────────────
        RECT.set(l, t, r, b);
        PATH.reset();
        PATH.addRoundRect(RECT, radius, radius, Path.Direction.CW);
        int save = cv.save();
        try {
            cv.clipPath(PATH);
            P.reset();
            P.setAntiAlias(true);
            P.setStyle(Paint.Style.FILL);
            if (darkGlass(c)) {
                int base = lastCourseColor;
                float startY = t + h * 0.64f;
                P.setShader(new LinearGradient(0f, startY, 0f, b, new int[]{
                        0x00000000, withAlpha(base, 0x70), withAlpha(base, 0xF0)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRect(l, startY, r, b, P);
            } else {
                float glossH = h * tk.gloss;
                P.setShader(new LinearGradient(0f, t, 0f, t + glossH,
                        alpha(tk.sheen, k * 0.55f), 0x00000000, Shader.TileMode.CLAMP));
                cv.drawRect(l, t, r, t + glossH, P);
                float footH = h * 0.30f;
                P.setShader(new LinearGradient(0f, b - footH, 0f, b,
                        0x00000000, alpha(tk.rimSide, k * 0.9f), Shader.TileMode.CLAMP));
                cv.drawRect(l, b - footH, r, b, P);
            }
            P.setShader(null);
        } catch (Throwable e) {
            Logx.w(T, "凝光卡片剪切失败: " + e);
        } finally {
            cv.restoreToCount(save);
        }

        rim(cv, c, tk, l, t, r, b, radius, k, 1f, true);
        halo(cv, c, tk, l, t, r, b, radius, k);
    }

    /**
     * 卡片外的一圈柔光：宿主 InnerShadowStyle 的 shadow1（白、dy -22、半径 93、alpha 127、OVERLAY）
     * 是「凝光」里那道“溢出的光”。硬画 Overlay 混色太依赖 layer，这里用两层同心圆角描边近似，
     * 亮度按 alpha 递减，缩放到任何卡片尺寸都不会糊成一片。
     */
    private static void halo(Canvas cv, Context c, Tokens tk, float l, float t, float r, float b,
                             float radius, float k) {
        float dp = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < 2; i++) {
            float grow = dp * (2.5f + i * 3.5f);
            float a = (i == 0 ? 0x12 : 0x0A) * k;
            RECT.set(l - grow, t - grow * 1.6f, r + grow, b + grow);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(dp * 1.4f);
            P.setShader(null);
            P.setColor(alpha(0xFFFFFFFF, a / 255f));
            cv.drawRoundRect(RECT, radius + grow, radius + grow, P);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0);
        }
    }

    // ── 空格子 ────────────────────────────────────────────────────────────────

    /**
     * TableView#drawEmptyCourse 之后调用：按宿主同一套几何（cellStartLeft 起步、每格
     * cellWidth - spaceWeiht）把这一行的格子逐格描一圈弱一档的凝光。
     *
     * @param startLeft 调用前的 cellStartLeft（宿主的循环会把它推到最后）
     * @param top       调用前的 cellStartTop
     */
    static void emptyRow(Canvas cv, Context c, Object table, float startLeft, float top) {
        if (cv == null || c == null || table == null) return;
        if (!ThemeStore.ningguang(c)) return;
        Tokens tk = tokens(c);
        float cellW = num(Ref.field(table, "cellWidth"), 0f);
        float cellH = num(Ref.field(table, "cellHeigt"), 0f);
        float space = num(Ref.field(table, "spaceWeiht"), 0f);
        float radius = num(Ref.field(table, "courseRadius"), 6f);
        int days = (int) num(Ref.field(table, "dayNumberOfWeek"), 0f);
        if (cellW <= 0f || cellH <= 0f || days <= 0) return;
        float k = (night(c) ? 0.55f : 0.62f);
        float left = startLeft;
        for (int i = 0; i < days; i++) {
            float right = left + cellW - space;
            float bottom = top + cellH - space;
            if (right - left > 4f && bottom - top > 4f) {
                float rr = Math.min(radius, 8f);
                if (ThemeStore.style(c) == ThemeStore.STYLE_LIQUID) {
                    // 液态玻璃：空格子只留一圈极淡的 Fresnel 白线（蓝折射留给课程卡，
                    // 一格一格描蓝边会花掉整个网格）
                    float lw = Math.max(1f, 0.5f * c.getResources().getDisplayMetrics().density);
                    RECT.set(left + lw * 0.5f, top + lw * 0.5f, right - lw * 0.5f, bottom - lw * 0.5f);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(lw);
                    P.setShader(null);
                    P.setColor(alpha(0xFFFFFFFF, night(c) ? 0.10f : 0.14f));
                    cv.drawRoundRect(RECT, Math.max(0f, rr - lw * 0.5f),
                            Math.max(0f, rr - lw * 0.5f), P);
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(0);
                } else if (!NingGuangShader.draw(cv, c, tk, left, top, right, bottom, rr,
                        night(c) ? 0.30f : 0.46f, false)) {
                    rim(cv, c, tk, left, top, right, bottom, rr, k, 0.8f, false);
                }
            }
            left = right + space;
        }
    }

    /** 轮廓光：一圈圆角描边，渐变从上沿亮边 → 侧边弱光 → 下沿折射 */
    private static void rim(Canvas cv, Context c, Tokens tk, float l, float t, float r, float b,
                            float radius, float k, float strength, boolean blue) {
        float w = Math.max(1f, dp(c, tk.rimDp));
        RECT.set(l + w * 0.5f, t + w * 0.5f, r - w * 0.5f, b - w * 0.5f);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(w);
        P.setShader(new LinearGradient(0f, t, 0f, b, new int[]{
                alpha(tk.rimNear, k * strength * 0.92f),
                alpha(tk.rimSide, k * strength * 0.75f),
                alpha(blue ? tk.rimFar : tk.rimSide, k * strength * 0.85f)},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRoundRect(RECT, Math.max(0f, radius - w * 0.5f), Math.max(0f, radius - w * 0.5f), P);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
    }

    // ── 空格子半透明（有背景时才透） ─────────────────────────────────────────

    /**
     * 空格子底色：宿主用的是实心 R.color.empty_course_bg，一铺就把背景图/纯色底全挡住
     * （「设了背景图看不见」「遮罩怎么调都没反应」都是这个）。
     * 有主题背景时换成半透明白，格子立刻变成磨砂玻璃；无背景时原样返回。
     */
    static int emptyCellTint(Context c, int original) {
        if (c == null) return original;
        int type = ThemeStore.type(c);
        if (type == ThemeStore.TYPE_NONE) return original;
        // 有底图时格子几乎全透（参考 ColorOS 课表：底图直接透上来，只留一圈轮廓光）
        int a = night(c) ? 0x12 : 0x14;
        // 保留宿主底色的色相（浅灰），只换掉不透明度
        return Color.argb(a, Color.red(original), Color.green(original), Color.blue(original));
    }

    // ── 卡片底色 / 字色（玻璃化） ─────────────────────────────────────────────

    /**
     * 课表正在绘制课程卡片（由 Hooks 在 TableView#drawCourse 前后置位）。
     * TodoColor 的 getBgColor/getTitleColor 同时被待办等界面用，只有在这个窗口里才改。
     */
    static volatile boolean inTable;

    /** 卡片白底那支 Paint（TableView.whiteBgPaing）：玻璃模式下把它刷成透明 */
    static volatile Object basePaint;

    static boolean glass(Context c) {
        return c != null && ThemeStore.ningguang(c);
    }

    /** 有背景（纯色/渐变/图片）才做「深色玻璃 + 白字」；无背景保持浅色卡 + 深字 */
    static boolean darkGlass(Context c) {
        return ThemeStore.type(c) != ThemeStore.TYPE_NONE;
    }

    /** 课程卡底色：有背景 → 卡片主体**全透明**（底图直接透上来），颜色交给底下 1/3 的渐变；无背景 → 原样不透明 */
    static int cardFill(Context c, int orig) {
        if (!glass(c) || !darkGlass(c)) return orig;
        return 0x00000000;
    }

    /**
     * 宿主原始课程色（由 z0 取色 hook 记下）。
     * 卡片画序固定是「底色取色 → 画圆角 → （我们）画凝光 → 画文字」，同一线程同一次绘制里取用即正确。
     */
    static volatile int lastCourseColor = 0xFF3A7AFF;

    /**
     * 课程卡文字：**跟着底图明暗走**，不再一律刷白。
     * 亮底（含遮罩后的等效亮度）用宿主原本的深色标题色，暗底才用近白 —— 这样既不加灰色蒙版，
     * 字也不会糊在底图上。判据与顶栏/时间轴同一套（{@link ThemeBg#isDarkBackground}）。
     */
    /**
     * 课程卡文字：
     * <ul>
     *   <li><b>凝光</b>：卡片主体是「磨砂 + 乳白混色」（couiMaterial），亮度跟深浅色走，
     *       所以字色也只跟深浅色走 —— 不再看底图亮度（深色图上的乳白卡配白字会糊）；</li>
     *   <li><b>液态玻璃</b>：卡片里是<b>透过去且被弯折的底图原色</b>，亮度取决于底图，
     *       所以仍按底图明暗选深/浅字。</li>
     * </ul>
     */
    static int cardInk(Context c, int orig) {
        if (!glass(c) || !darkGlass(c)) return orig;
        if (ThemeStore.style(c) == ThemeStore.STYLE_NINGGUANG) {
            return night(c) ? 0xFFF5F7FA : 0xFF1A1C1F;
        }
        boolean darkBg = ThemeBg.isDarkBackground(c, ThemeStore.type(c));
        return darkBg ? 0xFFF5F7FA : 0xFF1A1C1F;
    }

    // ── 课程色板：宿主 TodoColor 的 bg/title 存的是**资源 id**，不是颜色 ─────────

    private static volatile java.util.Set<Integer> cardFillIds;
    private static volatile java.util.Set<Integer> cardInkIds;

    /**
     * 从宿主的 {@code com.oplus.todotaskability.TodoColor} 枚举里收集 12 组课程的
     * bgColor / titleColor **资源 id**（宿主的 drawCourseItem 把 getBgColor() 直接喂给
     * {@code z0.c(resId, …)} 去 getColor —— 所以这里只能认 id，返回颜色会把宿主搞崩：
     * 实测 Resources$NotFoundException #0xa603063d，见 logs\deploy-20261115-134308.log）。
     */
    static void palette(ClassLoader host) {
        if (cardFillIds != null) return;
        synchronized (NingGuang.class) {
            if (cardFillIds != null) return;
            java.util.HashSet<Integer> fills = new java.util.HashSet<>();
            java.util.HashSet<Integer> inks = new java.util.HashSet<>();
            try {
                Class<?> tc = Class.forName("com.oplus.todotaskability.TodoColor", false, host);
                java.lang.reflect.Method entries = null;
                for (java.lang.reflect.Method m : tc.getDeclaredMethods()) {
                    if (m.getName().equals("getEntries") && m.getParameterCount() == 0) {
                        entries = m;
                        break;
                    }
                }
                Object list = entries == null ? null : entries.invoke(null);
                if (list instanceof Iterable) {
                    for (Object e : (Iterable<?>) list) {
                        Object bg = Ref.callName(e, "getBgColor");
                        Object ink = Ref.callName(e, "getTitleColor");
                        if (bg instanceof Integer) fills.add((Integer) bg);
                        if (ink instanceof Integer) inks.add((Integer) ink);
                    }
                }
            } catch (Throwable t) {
                Logx.w(T, "课程色板收集失败: " + t);
            }
            cardFillIds = fills;
            cardInkIds = inks;
            Logx.i(T, "课程色板：bg=" + fills.size() + " title=" + inks.size());
        }
    }

    static boolean isCardFill(int resId) {
        java.util.Set<Integer> s = cardFillIds;
        return s != null && s.contains(resId);
    }

    static boolean isCardInk(int resId) {
        java.util.Set<Integer> s = cardInkIds;
        return s != null && s.contains(resId);
    }

    private static int darken(int color, float k) {
        int r = Math.round(Color.red(color) * k);
        int g = Math.round(Color.green(color) * k);
        int b = Math.round(Color.blue(color) * k);
        return Color.rgb(r, g, b);
    }

    /** 往白色掺 k（0..1）：底部那条「白透明的原课程色」就是它 */
    private static int blendWhite(int color, float k) {
        float t = clamp(k, 0f, 1f);
        int r = Math.round(Color.red(color) + (255 - Color.red(color)) * t);
        int g = Math.round(Color.green(color) + (255 - Color.green(color)) * t);
        int b = Math.round(Color.blue(color) + (255 - Color.blue(color)) * t);
        return Color.rgb(r, g, b);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF RECT = new RectF();
    private static final Path PATH = new Path();

    private static int color(Resources r, String pkg, String name) {
        try {
            int id = r.getIdentifier(name, "color", pkg);
            return id == 0 ? 0 : r.getColor(id, null);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static float dimen(Resources r, String pkg, String name, float def) {
        try {
            int id = r.getIdentifier(name, "dimen", pkg);
            if (id == 0) return def;
            return r.getDimension(id) / r.getDisplayMetrics().density;
        } catch (Throwable e) {
            return def;
        }
    }

    private static int alpha(int color, float k) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp(k, 0f, 1f));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int a) {
        return ((a & 0xFF) << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static float num(Object o, float def) {
        return o instanceof Number ? ((Number) o).floatValue() : def;
    }

    /** 一格凝光的几何（供日志/自检用） */
    static String describe(Context c) {
        Tokens tk = tokens(c);
        return tk.source + " rim=" + tk.rimDp + "dp sheen="
                + String.format("#%08X", tk.sheen) + " near=" + String.format("#%08X", tk.rimNear)
                + " far=" + String.format("#%08X", tk.rimFar)
                + " 材质=" + NativeMaterial.dump(NativeMaterial.get(c));
    }

    /** 让当前课表重画（切凝光开关 / 换遮罩后调用） */
    static void invalidate(View v) {
        if (v == null) return;
        v.post(v::invalidate);
    }
}
