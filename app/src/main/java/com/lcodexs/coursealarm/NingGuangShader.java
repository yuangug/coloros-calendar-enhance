package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * ColorOS 17「凝光视效」材质 —— 一整条 GPU 管线（AGSL / RuntimeShader）。
 *
 * <p>参考实现：<a href="https://github.com/ItosEO/COUI-Kit">COUI-Kit</a> 的
 * {@code coui/effect/CouiEdge.kt}（{@code blendMenuInnerGlow} + {@code blendEdge}）与
 * {@code CouiMaterial.kt}（{@code couiMaterial}：先 luminosity 再 overlay）。
 *
 * <p><b>它和液态玻璃是两回事</b>：凝光是光在<b>磨砂</b>面上的散射与反射 ——
 * 背景被模糊、被材质混色，边缘内侧起一圈晕、沿光向有一段轮廓亮线；背景内容
 * <b>不弯</b>。液态玻璃是光在<b>透镜</b>里的折射 —— 背景按 SDF 法线<b>位移重采样</b>，
 * 边缘出现色散，光只在一圈高光里。判据见 {@code analysis/findings-glass-materials.md}。
 *
 * <p>三步全在这一个 shader 里（每帧 GPU 现算，不烘图）：
 * <pre>
 *   1. 采样 + 9 taps 模糊（磨砂）
 *   2. couiMaterial：luminosity(mixColor) ⇒ overlay(blendColor)（材质混色 = 「凝」）
 *   3. 内晕（边缘内侧提亮）+ 轮廓光（按 SDF 法线与光向给 near/far/side 三段）
 * </pre>
 */
final class NingGuangShader {
    private static final String T = CourseAlarmEntry.TAG;

    /** 磨砂半径（dp）：凝光要「雾」，但不能糊到看不出背景 */
    private static final float BLUR_DP = 7f;
    /** 内晕宽度：相对卡片短边 */
    private static final float GLOW_RATIO = 0.42f;
    /** 轮廓线宽（dp） */
    private static final float RIM_DP = 1.2f;
    /** 光从左上来 */
    private static final float LIGHT_ANGLE_DEG = 45f;

    private static RuntimeShader shader;
    private static boolean tried;
    private static boolean ok;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();
    private static final Path PATH = new Path();

    private NingGuangShader() {
    }

    private static final String AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float2 mapScale;
            uniform float2 mapShift;
            uniform float blurRadius;
            uniform float vibrancy;
            uniform float4 mixColor;
            uniform float4 blendColor;
            uniform float glowWidth;
            uniform float glowAlpha;
            uniform float rimWidth;
            uniform float4 rimNear;
            uniform float4 rimFar;
            uniform float4 rimSide;
            uniform float lightAngle;
            uniform float falloff;
            uniform float strength;
            uniform float fill;

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
                    return sign(coord) * (v / max(length(v), 0.0001));
                } else {
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }
            }

            float lum(float3 c) {
                return dot(c, float3(0.3, 0.59, 0.11));
            }
            float3 luminosity(float3 source, float3 destination) {
                float destinationLum = lum(destination);
                float sourceLum = lum(source);
                float3 color = destination + (sourceLum - destinationLum);
                float minColor = min(min(color.x, color.y), color.z);
                float maxColor = max(max(color.x, color.y), color.z);
                if (minColor < 0.0) {
                    return sourceLum + ((color - sourceLum) * sourceLum) / max(sourceLum - minColor, 0.0001);
                } else if (maxColor > 1.0) {
                    return sourceLum + ((color - sourceLum) * (1.0 - sourceLum)) / max(maxColor - sourceLum, 0.0001);
                }
                return color;
            }
            float overlay1(float s, float d) {
                return d < 0.5 ? 2.0 * s * d : 1.0 - 2.0 * (1.0 - s) * (1.0 - d);
            }
            half4 couiMaterial(half4 inColor, float4 mixC, float4 blendC) {
                float3 luminosityColor = luminosity(mixC.rgb, inColor.rgb);
                inColor.rgb = mix(inColor.rgb, luminosityColor, mixC.a);
                float3 overlayColor = float3(
                        overlay1(blendC.r, inColor.r),
                        overlay1(blendC.g, inColor.g),
                        overlay1(blendC.b, inColor.b));
                inColor.rgb = mix(inColor.rgb, overlayColor, blendC.a);
                return inColor;
            }

            float2 toSrc(float2 coord) {
                return (coord + mapShift) * mapScale;
            }
            half4 sampleAt(float2 coord) {
                half4 s = content.eval(toSrc(coord));
                if (s.a < 0.02) {
                    // 采样落空（位图正在重建 / 越界）。上游 couiMaterial 的 luminosity
                    // 会把「空」抬成一团灰白 —— 那正是「整张卡突然闪白」的来源。
                    return half4(0.0);
                }
                float l = lum(float3(s.rgb));
                s.rgb = mix(float3(l), s.rgb, vibrancy);
                return s;
            }
            half4 sampleBlur(float2 coord) {
                if (blurRadius < 0.5) return sampleAt(coord);
                float2 r = float2(blurRadius, 0.0);
                float2 d = r * 0.7071;
                half4 s = sampleAt(coord) * 0.2;
                s += sampleAt(coord + r) * 0.12;
                s += sampleAt(coord - r) * 0.12;
                s += sampleAt(coord + float2(0.0, blurRadius)) * 0.12;
                s += sampleAt(coord - float2(0.0, blurRadius)) * 0.12;
                s += sampleAt(coord + d) * 0.08;
                s += sampleAt(coord + float2(d.x, -d.y)) * 0.08;
                s += sampleAt(coord + float2(-d.x, d.y)) * 0.08;
                s += sampleAt(coord - d) * 0.08;
                return s;
            }
            float hash(float2 p) {
                return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
            }

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(centeredCoord, cornerRadii);
                float sd = sdRoundedRect(centeredCoord, halfSize, radius);

                float2 gradRadiusDir = centeredCoord + float2(0.0001, 0.0001);
                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

                float2 normal = float2(cos(lightAngle), sin(lightAngle));
                float dotL = dot(normalize(gradRadiusDir), normal);
                float rimMask = smoothstep(-rimWidth * 2.0, 0.0, sd);
                float near = pow(max(dotL, 0.0), falloff) * rimMask;
                float far = pow(max(-dotL, 0.0), falloff) * rimMask * 0.75;
                float side = (1.0 - abs(dotL)) * rimMask * 0.45;

                if (fill < 0.5) {
                    float3 rimRgb = mix(rimSide.rgb, rimNear.rgb, near / max(near + side, 0.0001));
                    rimRgb = mix(rimRgb, rimFar.rgb, far / max(far + side, 0.0001));
                    float a = clamp(near + far + side, 0.0, 1.0) * strength;
                    return half4(rimRgb * a, a);
                }

                half4 col = couiMaterial(sampleBlur(coord), mixColor, blendColor);
                if (col.a < 0.05) {
                    return half4(0.0);           // 采样全落空：宁可不画，也不要糊一层白
                }

                float t = clamp(1.0 + sd / max(glowWidth, 0.0001), 0.0, 1.0);
                float glow = pow(t, 2.4) * glowAlpha * strength;
                col.rgb = col.rgb + float3(glow);

                col.rgb = mix(col.rgb, rimNear.rgb, clamp(near, 0.0, 1.0) * rimNear.a * strength);
                col.rgb = mix(col.rgb, rimFar.rgb, clamp(far, 0.0, 1.0) * rimFar.a * strength);
                col.rgb = mix(col.rgb, rimSide.rgb, clamp(side, 0.0, 1.0) * rimSide.a * strength);

                col.rgb += (hash(coord) - 0.5) * 0.006;
                return col;
            }
            """;

    static boolean ensure() {
        if (tried) return ok;
        tried = true;
        if (Build.VERSION.SDK_INT < 33) {
            Logx.w(T, "凝光：SDK " + Build.VERSION.SDK_INT + " < 33，退回几何近似");
            return false;
        }
        try {
            shader = new RuntimeShader(AGSL);
            ok = true;
            Logx.i(T, "凝光：AGSL 就绪（blur + couiMaterial + innerGlow + contour）");
        } catch (Throwable t) {
            shader = null;
            ok = false;
            Logx.e(T, "凝光：AGSL 编译失败，退回几何近似", t);
        }
        return ok;
    }

    static boolean ready() {
        return ensure() && Backdrop.shader() != null;
    }

    /**
     * 画一张凝光卡片（调用点在「卡片底色已画、课程文字未画」之间）。
     *
     * @param strength 强度：课程卡 1.0，空格子 0.45
     * @param fill     true = 材质 + 轮廓光；false = 只画轮廓光（空格子不要一块块糊住底图）
     */
    static boolean draw(Canvas cv, Context c, NingGuang.Tokens tk,
                        float l, float t, float r, float b, float radius,
                        float strength, boolean fill) {
        if (cv == null || c == null || tk == null) return false;
        if (!ready()) return false;
        BitmapShader bs = Backdrop.shader();
        if (bs == null) return false;

        float w = r - l;
        float h = b - t;
        if (w < 4f || h < 4f) return false;
        float density = c.getResources().getDisplayMetrics().density;
        boolean night = NingGuang.night(c);
        float minDim = Math.min(w, h);

        // 模糊 / 两层混色：全部取自宿主底栏（COUIMaterialEffect 的 TYPE_FRAMEWORK_BOTTOM_BAR）
        NativeMaterial nm = NativeMaterial.get(c);
        float blur = Math.min(nm.blurDp * density, minDim * 0.62f);
        float glowW = Math.max(minDim * GLOW_RATIO, 8f * density);
        float rimW = Math.max(1f, RIM_DP * density);
        shader.setFloatUniform("mixColor",
                ((nm.layer1 >> 16) & 0xFF) / 255f,
                ((nm.layer1 >> 8) & 0xFF) / 255f,
                (nm.layer1 & 0xFF) / 255f,
                ((nm.layer1 >>> 24) & 0xFF) / 255f);
        shader.setFloatUniform("blendColor",
                ((nm.layer2 >> 16) & 0xFF) / 255f,
                ((nm.layer2 >> 8) & 0xFF) / 255f,
                (nm.layer2 & 0xFF) / 255f,
                ((nm.layer2 >>> 24) & 0xFF) / 255f);

        shader.setInputShader("content", bs);
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("offset", -l, -t);
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        shader.setFloatUniform("mapScale", Backdrop.mapScale(), Backdrop.mapScale());
        shader.setFloatUniform("mapShift", Backdrop.offX(), Backdrop.offY());
        shader.setFloatUniform("blurRadius", blur);
        shader.setFloatUniform("vibrancy", 1.08f);
        shader.setFloatUniform("glowWidth", glowW);
        shader.setFloatUniform("glowAlpha", (night ? 0.26f : 0.34f) * strength);
        shader.setFloatUniform("rimWidth", rimW);
        shader.setFloatUniform("lightAngle", (float) (LIGHT_ANGLE_DEG * Math.PI / 180.0));
        shader.setFloatUniform("falloff", 1.6f);
        shader.setFloatUniform("strength", strength);
        shader.setFloatUniform("fill", fill ? 1f : 0f);
        color4("rimNear", tk.rimNear, 0.85f);
        color4("rimFar", tk.rimFar, 0.55f);
        color4("rimSide", tk.rimSide, 1.4f);

        RECT.set(l, t, r, b);
        PATH.reset();
        PATH.addRoundRect(RECT, radius, radius, Path.Direction.CW);
        int save = cv.save();
        try {
            cv.clipPath(PATH);
            P.reset();
            P.setStyle(Paint.Style.FILL);
            P.setShader(shader);
            cv.drawRect(l, t, r, b, P);
            P.setShader(null);
            return true;
        } catch (Throwable e) {
            Logx.w(T, "凝光：绘制失败 " + e);
            return false;
        } finally {
            cv.restoreToCount(save);
        }
    }

    /** 顶部凝光（宿主 InnerShadowStyle 那道自上而下的光）：shader 之上再叠一层很薄的渐变 */
    static void sheen(Canvas cv, Context c, NingGuang.Tokens tk, float l, float t, float r, float b,
                      float radius, float strength) {
        if (c == null || tk == null) return;
        float h = b - t;
        if (h < 8f) return;
        float k = NingGuang.night(c) ? 0.55f : 1f;
        int save = cv.save();
        try {
            RECT.set(l, t, r, b);
            PATH.reset();
            PATH.addRoundRect(RECT, radius, radius, Path.Direction.CW);
            cv.clipPath(PATH);
            float glossH = h * (0.45f * tk.gloss);
            P.reset();
            P.setStyle(Paint.Style.FILL);
            P.setShader(new LinearGradient(0f, t, 0f, t + glossH,
                    alpha(tk.sheen, k * 0.55f * strength), 0x00000000, Shader.TileMode.CLAMP));
            cv.drawRect(l, t, r, t + glossH, P);
            P.setShader(null);
        } finally {
            cv.restoreToCount(save);
        }
    }

    private static void color4(String name, int argb, float mult) {
        float a = ((argb >>> 24) & 0xFF) / 255f * mult;
        shader.setFloatUniform(name,
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                Math.min(1f, a));
    }

    private static int alpha(int color, float k) {
        float v = Math.max(0f, Math.min(1f, k));
        return (Math.round(((color >>> 24) & 0xFF) * v) << 24) | (color & 0x00FFFFFF);
    }
}
