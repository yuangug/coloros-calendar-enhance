package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 直接借用宿主自己的「凝光材质」参数 —— 底栏那一套。
 *
 * <p>用户要求凝光「参考复用应用原生的底栏效果」，所以不自己编参数，而是进宿主进程里
 * 把 COUI 的材质表读出来：
 *
 * <pre>
 *   com.coui.appcompat.COUIMaterialEffect#getBlurParamsByType(Context, BlurEffectType)
 *      BlurEffectType.TYPE_FRAMEWORK_BOTTOM_BAR   ← 就是底栏用的那一档
 *   返回 COUIMaterialBlurEffect.BlurEffectParams:
 *      blurRadius     模糊半径
 *      colorLayer1    + blendModeLayer1   第一层（材质混色）
 *      colorLayer2    + blendModeLayer2   第二层（叠加）
 * </pre>
 *
 * <p>宿主 17.3.70 里这两档的原始值（{@code COUIMaterialBlurEffect} 静态字段，写在下面做兜底）：
 *
 * <pre>
 *   LIGHT: blur=25  color1=#B3666666  color2=#99F5F5F5
 *   DARK : blur=25  color1=#80666666  color2=#B3262626
 * </pre>
 *
 * <p>取不到（换机型 / 版本漂移 / 类被裁）就用上面的兜底值，视觉不会崩。
 */
final class NativeMaterial {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_EFFECT = "com.coui.appcompat.COUIMaterialEffect";
    private static final String CLS_BLUR = "com.coui.appcompat.COUIMaterialBlurEffect";
    private static final String TYPE_BOTTOM_BAR = "TYPE_FRAMEWORK_BOTTOM_BAR";

    /** 兜底：宿主 17.3.70 的底栏参数 */
    private static final float FALLBACK_BLUR = 25f;
    private static final int FALLBACK_L1_LIGHT = 0xB3666666;
    private static final int FALLBACK_L2_LIGHT = 0x99F5F5F5;
    private static final int FALLBACK_L1_DARK = 0x80666666;
    private static final int FALLBACK_L2_DARK = 0xB3262626;

    /** 单位：宿主给的是模糊半径，按 dp 解释（与 COUI-Kit 的 25 一致） */
    float blurDp = FALLBACK_BLUR;
    int layer1;
    int layer2;
    int blend1 = -1;
    int blend2 = -1;
    boolean fromHost;
    String source = "fallback";

    private static volatile NativeMaterial cached;
    private static volatile int cacheKey = Integer.MIN_VALUE;

    private NativeMaterial() {
    }

    /** 按「深浅色 + 密度」缓存；宿主材质表本身是静态常量，不会中途变 */
    static NativeMaterial get(Context c) {
        if (c == null) return fallback(false);
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int key = (night ? 1 : 0)
                | (c.getResources().getConfiguration().densityDpi << 1);
        NativeMaterial m = cached;
        if (m != null && cacheKey == key) return m;
        synchronized (NativeMaterial.class) {
            if (cached != null && cacheKey == key) return cached;
            NativeMaterial nm = read(c, night);
            cached = nm;
            cacheKey = key;
            Logx.i(T, "凝光材质 ← " + nm.source + " blur=" + nm.blurDp + "dp"
                    + " L1=" + String.format("#%08X", nm.layer1)
                    + " L2=" + String.format("#%08X", nm.layer2)
                    + " blend=" + nm.blend1 + "/" + nm.blend2);
            return nm;
        }
    }

    private static NativeMaterial fallback(boolean night) {
        NativeMaterial m = new NativeMaterial();
        m.layer1 = night ? FALLBACK_L1_DARK : FALLBACK_L1_LIGHT;
        m.layer2 = night ? FALLBACK_L2_DARK : FALLBACK_L2_LIGHT;
        return m;
    }

    private static NativeMaterial read(Context c, boolean night) {
        NativeMaterial m = fallback(night);
        if (Build.VERSION.SDK_INT < 33) return m;
        try {
            ClassLoader host = c.getClassLoader();
            Class<?> eff = Ref.cls(host, CLS_EFFECT);
            Class<?> blur = Ref.cls(host, CLS_BLUR);
            if (eff == null || blur == null) return m;
            Class<?> typeCls = Ref.cls(host, CLS_BLUR + "$BlurEffectType");
            if (typeCls == null) return m;
            Object type = null;
            for (Object o : typeCls.getEnumConstants()) {
                if (TYPE_BOTTOM_BAR.equals(String.valueOf(o))) {
                    type = o;
                    break;
                }
            }
            if (type == null) return m;
            Method get = null;
            for (Method mm : eff.getDeclaredMethods()) {
                if ("getBlurParamsByType".equals(mm.getName()) && mm.getParameterCount() == 2) {
                    get = mm;
                    break;
                }
            }
            if (get == null) return m;
            get.setAccessible(true);
            Object params = get.invoke(null, c, type);
            if (params == null) return m;
            Object br = Ref.callName(params, "getBlurRadius");
            if (br instanceof Number) m.blurDp = ((Number) br).floatValue();
            Object c1 = Ref.callName(params, "getColorLayer1");
            Object c2 = Ref.callName(params, "getColorLayer2");
            int p1 = parseColor(c1);
            int p2 = parseColor(c2);
            if (p1 != 0) m.layer1 = p1;
            if (p2 != 0) m.layer2 = p2;
            Object b1 = Ref.callName(params, "getBlendModeLayer1");
            Object b2 = Ref.callName(params, "getBlendModeLayer2");
            if (b1 != null) m.blend1 = ord(b1);
            if (b2 != null) m.blend2 = ord(b2);
            m.fromHost = true;
            m.source = "host:" + type;
        } catch (Throwable t) {
            Logx.w(T, "凝光材质：读宿主底栏参数失败，用兜底 " + t);
        }
        return m;
    }

    /** BlendMode 枚举：取 ordinal 当 id 用（AGSL 里只认我们实现的那几种） */
    private static int ord(Object e) {
        try {
            Object v = Ref.callName(e, "ordinal");
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** "#AARRGGBB" / "#RRGGBB" / 已有 int */
    private static int parseColor(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (!(o instanceof String)) return 0;
        String s = ((String) o).trim();
        if (s.isEmpty()) return 0;
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 6) {
                    return 0xFF000000 | (int) Long.parseLong(h, 16);
                }
                if (h.length() == 8) {
                    return (int) Long.parseLong(h, 16);
                }
            }
            return Color.parseColor(s);
        } catch (Throwable t) {
            Logx.w(T, "凝光材质：颜色解析失败 " + s);
            return 0;
        }
    }

    static String dump(NativeMaterial m) {
        if (m == null) return "-";
        return String.format(Locale.US, "%s blur=%.0fdp L1=#%08X L2=#%08X",
                m.source, m.blurDp, m.layer1, m.layer2);
    }
}
