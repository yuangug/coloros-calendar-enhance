package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 课程卡的「液态玻璃」材质 —— 真折射（AGSL / RuntimeShader，GPU 每帧现算）。
 *
 * <p>这一版按 <a href="https://github.com/Kyant0/AndroidLiquidGlass">Kyant0/AndroidLiquidGlass</a>
 * 的 <b>backdrop</b> 库整条链路对齐（文档：kyant.gitbook.io/backdrop）：
 *
 * <pre>
 *   colorFilter(vibrancy) ⇒ blur(仅凝光要) ⇒ lens(refraction [+dispersion])
 *   + Highlight（描边 = 2×width，Plus 混合，沿 SDF 梯度的一道光）
 *   + InnerShadow（玻璃厚度）
 *   + onDrawSurface（半透明白，可读性）
 * </pre>
 *
 * <p>与上一版的三个关键差别：
 *
 * <ol>
 *   <li><b>采样源换掉了</b>：不再是「自己把主题背景再画一遍 + box blur 烘死」的缓存图，
 *       而是 {@link Backdrop} 逐帧从页面真实挂着的 View 抓的位图（未模糊）。液态玻璃靠
 *       <b>位移重采样</b>出玻璃感，先糊一遍就再也看不到弯折——上一版就是糊死的。</li>
 *   <li><b>参数按形状尺寸走</b>：上一版写死 24dp/48dp，而课表格子只有 ~40dp 宽，
 *       整张卡都落在折射带里，出来是一块发白的雾。现在 height ≈ 0.3×短边、
 *       amount ≈ 0.62×短边，并夹在圆角半径以内（backdrop 文档：height 超过圆角半径
 *       会在角上出现不连续）。</li>
 *   <li><b>高光按官方做法</b>：描边宽度 = 2×highlight width，靠 clip 只留内半边，
 *       Plus 叠加 + 极小 BlurMaskFilter。上一版手工加 bandWidth 遮罩，边缘永远是死白。</li>
 * </ol>
 *
 * <p>SDK &lt; 33（没有 RuntimeShader）时退回几何近似，视觉可以差但绝不崩。
 */
final class LiquidGlass {
    private static final String T = CourseAlarmEntry.TAG;

    // ── 参数（比例都相对卡片短边，换尺寸不用改代码） ──────────────────────────
    /**
     * 折射带宽度：0.16 × 短边。
     *
     * <p>参照 backdrop 官方 catalog 的 GlassPlayground：height = 0.1×短边、amount = 0.2×短边。
     * 卡片比示例的方块窄，取到 0.16 / 0.26（1:1.6）——再大一点网格线就会被拧成拱形，
     * 实测 0.36 / 0.72 那组直接把格线弯成了半圆，属于「过头」。
     */
    private static final float REFRACT_HEIGHT_RATIO = 0.16f;
    /** 折射位移量：1.6 × 带宽度 */
    private static final float REFRACT_AMOUNT_RATIO = 0.26f;
    /** 高光半宽（官方 Highlight.width 默认 0.5dp） */
    private static final float HL_WIDTH_DP = 0.55f;
    private static final float HL_ALPHA = 0.78f;
    private static final float HL_FALLOFF = 1.15f;
    /** 光从左上来（AGSL 里 angle 是与 SDF 梯度点积的方向） */
    private static final float HL_ANGLE_DEG = 45f;

    private LiquidGlass() {
    }

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();
    private static final Path PATH = new Path();

    // ── shader ────────────────────────────────────────────────────────────────

    private static RuntimeShader lens;
    private static RuntimeShader lensPlain;
    private static RuntimeShader highlight;
    private static RuntimeShader innerShadow;
    private static boolean tried;
    private static boolean ok;

    /** 圆角矩形 SDF（与 backdrop 库 internal/Shaders.kt 逐行一致） */
    private static final String SDF = """
            float radiusAt(float2 centeredCoord, float4 radii) {
                if (centeredCoord.x >= 0.0) {
                    if (centeredCoord.y <= 0.0) return radii.y;
                    else return radii.z;
                } else {
                    if (centeredCoord.y <= 0.0) return radii.x;
                    else return radii.w;
                }
            }
            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                float outside = length(max(cornerCoord, 0.0)) - radius;
                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                return outside + inside;
            }
            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    float2 v = max(cornerCoord, 0.0);
                    // 除以 length 而不是 normalize：cornerCoord 恰好为 0 时 normalize 会给 NaN，
                    // NaN 一路传到 intensity → 某些帧整张卡闪白
                    return sign(coord) * (v / max(length(v), 0.0001));
                } else {
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }
            }
            """;

    /** 折射 + 7 次采样色散：RoundedRectRefractionWithDispersionShaderString 的同构实现 */
    private static final String LENS_AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;
            uniform float chromaticAberration;
            uniform float2 mapScale;
            uniform float2 mapShift;
            uniform float vibrancy;

            """ + SDF + """

            float circleMap(float x) {
                return 1.0 - sqrt(max(1.0 - x * x, 0.0));
            }
            float2 toSrc(float2 coord) {
                return (coord + mapShift) * mapScale;
            }
            half4 sampleAt(float2 coord) {
                half4 s = content.eval(toSrc(coord));
                if (s.a < 0.02) {
                    return half4(0.0);           // 采样落空（位图重建中/越界）：透明，不要白
                }
                float l = dot(s.rgb, float3(0.299, 0.587, 0.114));
                s.rgb = mix(float3(l), s.rgb, vibrancy);
                return s;
            }

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(centeredCoord, cornerRadii);

                float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                if (-sd >= refractionHeight) {
                    return sampleAt(coord);
                }
                sd = min(sd, 0.0);

                float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 dir = centeredCoord + float2(0.0001, 0.0001);
                float2 grad = normalize(
                        gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
                        + depthEffect * normalize(dir));

                float2 refractedCoord = coord + d * grad;
                if (chromaticAberration <= 0.001) {
                    return sampleAt(refractedCoord);
                }
                float dispersionIntensity = chromaticAberration
                        * ((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 0.0001));
                float2 dispersedCoord = d * grad * dispersionIntensity;

                half4 color = half4(0.0);
                half4 red = sampleAt(refractedCoord + dispersedCoord);
                color.r += red.r / 3.5; color.a += red.a / 7.0;
                half4 orange = sampleAt(refractedCoord + dispersedCoord * (2.0 / 3.0));
                color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
                half4 yellow = sampleAt(refractedCoord + dispersedCoord * (1.0 / 3.0));
                color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
                half4 green = sampleAt(refractedCoord);
                color.g += green.g / 3.5; color.a += green.a / 7.0;
                half4 cyan = sampleAt(refractedCoord - dispersedCoord * (1.0 / 3.0));
                color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
                half4 blue = sampleAt(refractedCoord - dispersedCoord * (2.0 / 3.0));
                color.b += blue.b / 3.0; color.a += blue.a / 7.0;
                half4 purple = sampleAt(refractedCoord - dispersedCoord);
                color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
                return color;
            }
            """;

    /** 无圆角半径数组的退化版本（shader 编译失败时用不到，这里只留纯折射） */
    private static final String HIGHLIGHT_AGSL = """
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float4 color;
            uniform float angle;
            uniform float falloff;
            uniform float bandWidth;

            """ + SDF + """

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(centeredCoord, cornerRadii);
                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
                float2 normal = float2(cos(angle), sin(angle));
                float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                // 只在最外一圈出光：内部 intensity 恒为 1，不裁就会整张刷白。
                // 两层：line = 贴边的硬边线（Fresnel 圈，四周都有），band = 朝光那一侧的柔光。
                float dir = pow(abs(dot(grad, normal)), falloff);
                float band = 1.0 - smoothstep(max(bandWidth, 0.001) * 0.28, max(bandWidth, 0.001), -sd);
                float line = 1.0 - smoothstep(0.0, max(bandWidth * 0.22, 0.5), -sd);
                float intensity = (line * 0.45 + dir * band * 0.85) * color.a;
                return half4(float3(1.0) * intensity, intensity);
            }
            """;

    /** 玻璃厚度：内侧一圈暗（backdrop 的 InnerShadow：形状按 offset 平移后取外部为阴影） */
    private static final String INNER_SHADOW_AGSL = """
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float shadowRadius;
            uniform float2 shadowOffset;
            uniform float4 color;

            """ + SDF + """

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(centeredCoord, cornerRadii);
                float sd = sdRoundedRect(centeredCoord - shadowOffset, halfSize, radius);
                float a = smoothstep(0.0, max(shadowRadius, 0.001), sd) * color.a;
                return half4(color.rgb * a, a);
            }
            """;

    private static boolean ensure() {
        if (tried) {
            return ok;
        }
        tried = true;
        if (Build.VERSION.SDK_INT < 33) {
            Logx.w(T, "液态玻璃：SDK " + Build.VERSION.SDK_INT + " < 33，退回几何近似");
            return false;
        }
        try {
            highlight = new RuntimeShader(HIGHLIGHT_AGSL);
            innerShadow = new RuntimeShader(INNER_SHADOW_AGSL);
            try {
                lens = new RuntimeShader(LENS_AGSL);
            } catch (Throwable e) {
                Logx.w(T, "液态玻璃：色散版 AGSL 编译失败，试无位移色散版 " + e);
                lens = null;
            }
            ok = lens != null;
            Logx.i(T, "液态玻璃：AGSL 就绪 lens=" + (lens != null)
                    + " highlight=" + (highlight != null) + " innerShadow=" + (innerShadow != null));
        } catch (Throwable t) {
            lens = null;
            ok = false;
            Logx.e(T, "液态玻璃：AGSL 编译失败，退回几何近似", t);
        }
        return ok;
    }

    /** 有没有可能画出真玻璃（给 ThemeSheet 的提示文字用） */
    static boolean gpuReady() {
        return ensure();
    }

    // ── 每帧准备（由 Hooks 在 TableView#drawCourse 之前调用） ─────────────────

    static void prepare(Object tableView, Context c) {
        if (tableView instanceof View) {
            Backdrop.prepare(c, (View) tableView, null);
        }
    }

    // ── 卡片 ──────────────────────────────────────────────────────────────────

    static void card(Canvas cv, Context c, float l, float t, float r, float b, float rad) {
        if (cv == null || c == null) return;
        if (ThemeStore.style(c) != ThemeStore.STYLE_LIQUID) return;
        float w = r - l;
        float h = b - t;
        if (w < 6f || h < 6f) return;

        float density = c.getResources().getDisplayMetrics().density;
        float radius = Math.min(rad, Math.min(w, h) * 0.5f);
        boolean night = NingGuang.night(c);
        float k = night ? 0.92f : 1f;
        boolean gpu = ensure() && Backdrop.shader() != null;

        RECT.set(l, t, r, b);
        PATH.reset();
        PATH.addRoundRect(RECT, radius, radius, Path.Direction.CW);

        int save = cv.save();
        try {
            cv.clipPath(PATH);
            int stages = ThemeStore.glassStages(c);
            if (gpu) {
                if ((stages & ThemeStore.G_LENS) != 0) {
                    refraction(cv, l, t, w, h, radius, density, night);
                }
                if ((stages & ThemeStore.G_SURFACE) != 0) surface(cv, l, t, w, h, night);
                if ((stages & ThemeStore.G_HIGHLIGHT) != 0) {
                    highlight(cv, l, t, w, h, radius, density, k);
                }
                if ((stages & ThemeStore.G_INNER) != 0) {
                    innerShadow(cv, l, t, w, h, radius, density, night);
                }
            } else {
                fallback(cv, l, t, r, b, w, h, radius, density, k);
            }
            if ((stages & ThemeStore.G_COLLECT) != 0) {
                colorCollect(cv, l, t, r, b, w, h, k);
            }
        } catch (Throwable e) {
            Logx.w(T, "液态玻璃：绘制失败 " + e);
        } finally {
            cv.restoreToCount(save);
        }
    }

    /** 真折射：按 SDF 把背景位移重采样（GPU，每帧现算） */
    private static void refraction(Canvas cv, float l, float t, float w, float h,
                                   float radius, float density, boolean night) {
        float minDim = Math.min(w, h);
        float refH = Math.min(minDim * REFRACT_HEIGHT_RATIO, 14f * density);
        refH = Math.max(refH, 5f * density);
        refH = Math.min(refH, Math.max(radius * 1.8f, 5f * density));
        float refA = Math.min(minDim * REFRACT_AMOUNT_RATIO, 1.6f * refH);
        if (refH < 1f) return;

        BitmapShader bs = Backdrop.shader();
        if (bs == null) return;
        lens.setInputShader("content", bs);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("offset", -l, -t);
        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        lens.setFloatUniform("refractionHeight", refH);
        lens.setFloatUniform("refractionAmount", -refA);   // 官方传负值：梯度朝外、位移朝内
        lens.setFloatUniform("depthEffect", 1f);
        lens.setFloatUniform("chromaticAberration", minDim > 56f * density ? 0.34f : 0.24f);
        lens.setFloatUniform("mapScale", Backdrop.mapScale(), Backdrop.mapScale());
        lens.setFloatUniform("mapShift", Backdrop.offX(), Backdrop.offY());
        lens.setFloatUniform("vibrancy", 1.10f);

        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(lens);
        cv.drawRect(l, t, l + w, t + h, P);
        P.setShader(null);
    }

    /** onDrawSurface：半透明白（backdrop 官方 0.5，卡片上压到 0.10~0.16，否则整卡发白） */
    private static void surface(Canvas cv, float l, float t, float w, float h, boolean night) {
        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(null);
        P.setShader(new LinearGradient(0f, t, 0f, t + h, new int[]{
                night ? 0x14000000 : 0x22FFFFFF,
                night ? 0x08000000 : 0x10FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(l, t, l + w, t + h, P);
        P.setShader(null);
    }

    /**
     * 边缘高光：带内亮度按 |dot(SDF 梯度, 光向)| 走，亮度只出现在最外一圈。
     *
     * <p>官方是「描边 + BlurMaskFilter + Plus 混合」，这里改成 <b>FILL + shader 里的
     * band 遮罩</b>：HWUI(Vulkan) 上 maskFilter 会额外开一层，和 RuntimeShader 一起用
     * 实测会把整张课表的绘制吞掉（AdrenoVK: Shader compilation failed，页面全白），
     * 而且 shader 里的 smoothstep 本身就是最干净的柔边。
     */
    private static void highlight(Canvas cv, float l, float t, float w, float h,
                                  float radius, float density, float k) {
        if (highlight == null) return;
        float band = Math.min(Math.max(HL_WIDTH_DP * density * 2.2f, 1.6f * density), 6f * density);
        highlight.setFloatUniform("size", w, h);
        highlight.setFloatUniform("offset", -l, -t);
        highlight.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        highlight.setFloatUniform("color", 1f, 1f, 1f, HL_ALPHA * k);
        highlight.setFloatUniform("angle", (float) (HL_ANGLE_DEG * Math.PI / 180.0));
        highlight.setFloatUniform("falloff", HL_FALLOFF);
        highlight.setFloatUniform("bandWidth", band);

        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(highlight);
        try {
            cv.drawRect(l, t, l + w, t + h, P);
        } finally {
            P.setShader(null);
        }
    }

    /** 玻璃厚度：内侧一圈暗 */
    private static void innerShadow(Canvas cv, float l, float t, float w, float h,
                                    float radius, float density, boolean night) {
        if (innerShadow == null) return;
        float minDim = Math.min(w, h);
        float sr = Math.min(16f * density, minDim * 0.55f);
        if (sr < 1f) return;
        innerShadow.setFloatUniform("size", w, h);
        innerShadow.setFloatUniform("offset", -l, -t);
        innerShadow.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        innerShadow.setFloatUniform("shadowRadius", sr);
        innerShadow.setFloatUniform("shadowOffset", 0f, sr * 0.55f);
        innerShadow.setFloatUniform("color", 0f, 0f, 0f, night ? 0.30f : 0.16f);

        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(innerShadow);
        cv.drawRect(l, t, l + w, t + h, P);
        P.setShader(null);
    }

    /** 下沿聚色：课程自己的颜色，负责「这门课是哪个颜色」的识别 */
    private static void colorCollect(Canvas cv, float l, float t, float r, float b,
                                     float w, float h, float k) {
        int base = NingGuang.lastCourseColor;
        float startY = t + h * 0.72f;
        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(new LinearGradient(0f, startY, 0f, b, new int[]{
                0x00000000, withAlpha(base, (int) (0x30 * k)), withAlpha(base, (int) (0x9A * k))},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(l, startY, r, b, P);
        P.setShader(null);
    }

    /** 兜底（SDK < 33 / 没有采样源）：放大模糊背景的几何近似 */
    private static void fallback(Canvas cv, float l, float t, float r, float b,
                                 float w, float h, float radius, float density, float k) {
        P.reset();
        P.setStyle(Paint.Style.FILL);
        P.setShader(new RadialGradient(l + w * 0.5f, t + h * 0.35f,
                Math.max(w, h) * 0.75f, new int[]{0x38FFFFFF, 0x0AFFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(l, t, r, b, P);
        P.setShader(null);

        float width = Math.max(1.4f, 1.6f * density);
        RECT.set(l + width * 0.5f, t + width * 0.5f, r - width * 0.5f, b - width * 0.5f);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(width);
        P.setShader(new LinearGradient(l, t, r, b, new int[]{
                alpha(0xFFFFFFFF, 0.92f * k), alpha(0xFFFFFFFF, 0.16f * k),
                alpha(0xFFFFFFFF, 0.10f * k), alpha(0xFFFFFFFF, 0.50f * k)},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRoundRect(RECT, Math.max(0f, radius - width * 0.5f),
                Math.max(0f, radius - width * 0.5f), P);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
    }

    private static int alpha(int color, float a) {
        float v = Math.max(0f, Math.min(1f, a));
        return (Math.round(((color >>> 24) & 0xFF) * v) << 24) | (color & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int a) {
        return ((a & 0xFF) << 24) | (color & 0x00FFFFFF);
    }
}
