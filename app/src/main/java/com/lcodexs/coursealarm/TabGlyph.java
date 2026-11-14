package com.lcodexs.coursealarm;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏「课程表」那一格的图标：**同一份形状**，未选中描边（中空）、选中实心，
 * 与宿主 nav 两格（ic_bottom_nav_calendar_normal / _selected）同一套视觉规则。
 *
 * 为什么不直接用宿主的 vector：宿主只给了 {@code course_table_icon} 一个「实心」版本，
 * 没有 _normal / _selected 成对资源（见 analysis/findings-bar-17.3.70.md）。
 * 所以这里把它的 pathData 原样抄下来，自己解析成 {@link Path}，再用 FILL / STROKE
 * 两种画法分别出「实心」和「中空」——形状和现在屏幕上那格一模一样，不会变成另一个图标。
 *
 * pathData 来源：17.3.70 的 {@code res/drawable/course_table_icon.xml}（24dp 视口，evenOdd）。
 * 宿主换图标时这里不会自动跟着变（刻意：图标是我们自己的资产，不该随宿主漂移）。
 */
final class TabGlyph {
    private TabGlyph() {
    }

    /** 与宿主 res/drawable/course_table_icon.xml 完全一致的 pathData */
    private static final String PATH_DATA =
            "M21.15,9.847C20.085,9.341 19.35,8.256 19.35,7C19.35,5.744 20.085,4.659 21.15,4.153"
                    + "C21.272,4.095 21.35,3.972 21.35,3.837V3.5C21.35,3.331 21.23,3.19 21.07,3.157"
                    + "L21,3.15H5.317C3.275,3.246 1.65,4.936 1.65,7C1.65,9.065 3.276,10.75 5.317,10.846"
                    + "H5.333L21,10.85C21.093,10.85 21.181,10.813 21.247,10.747C21.313,10.681 21.35,10.593"
                    + " 21.35,10.5V10.163C21.35,10.028 21.272,9.905 21.15,9.847Z"
                    + "M18.873,20.836C20.825,20.648 22.35,19 22.35,17C22.35,14.935 20.724,13.25 18.683,13.154"
                    + "L18.679,13.154L18.672,13.154L18.667,13.154L3,13.15C2.907,13.15 2.819,13.187 2.753,13.253"
                    + "C2.687,13.319 2.65,13.407 2.65,13.5V13.837C2.65,13.972 2.728,14.095 2.85,14.153"
                    + "C3.915,14.659 4.65,15.744 4.65,17C4.65,18.177 4.004,19.204 3.046,19.745"
                    + "L2.85,19.847C2.728,19.905 2.65,20.028 2.65,20.163V20.5C2.65,20.593 2.687,20.681 2.753,20.747"
                    + "C2.819,20.813 2.907,20.85 3,20.85H18.683L18.873,20.836Z";

    private static volatile Path cached;
    private static volatile boolean failed;

    /** 解析好的字形（视口 24×24，evenOdd）。解析失败返回 null，调用方回落到宿主 vector */
    static Path path() {
        if (cached == null && !failed) {
            synchronized (TabGlyph.class) {
                if (cached == null && !failed) {
                    try {
                        Path p = parse(PATH_DATA);
                        p.setFillType(Path.FillType.EVEN_ODD);
                        cached = p;
                    } catch (Throwable t) {
                        failed = true;
                        Logx.w(CourseAlarmEntry.TAG, "TabGlyph 解析失败，回落宿主图标: " + t);
                    }
                }
            }
        }
        return cached;
    }

    /** 生成一格图标：filled=true 实心（选中），false 描边（未选中） */
    static Drawable make(boolean filled, int color, int sizePx) {
        Path p = path();
        if (p == null) return null;
        return new Icon(p, filled, color, sizePx);
    }

    // ── 画 ──────────────────────────────────────────────────────────────────

    private static final class Icon extends Drawable {
        private final Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        Icon(Path path, boolean filled, int color, int sizePx) {
            this.path = path;
            this.sizePx = sizePx;
            paint.setColor(color);
            if (filled) {
                paint.setStyle(Paint.Style.FILL);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.35f);      // 视口单位，随 canvas.scale 一起缩放
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float s = Math.min(b.width(), b.height()) / 24f;
            int save = canvas.save();
            canvas.translate(b.left + (b.width() - 24f * s) / 2f,
                    b.top + (b.height() - 24f * s) / 2f);
            canvas.scale(s, s);
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }

    // ── 极简 SVG path 解析（只用绝对 M/L/H/V/C/Z，够这份 pathData 用） ─────────

    private static Path parse(String d) {
        List<Object> tokens = tokenize(d);
        Path p = new Path();
        float x = 0f, y = 0f, sx = 0f, sy = 0f;
        char cmd = 0;
        int i = 0;
        while (i < tokens.size()) {
            Object t = tokens.get(i);
            if (t instanceof Character) {
                cmd = (Character) t;
                i++;
                continue;
            }
            switch (cmd) {
                case 'M':
                    x = num(tokens, i++);
                    y = num(tokens, i++);
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = 'L';              // M 后面的坐标对按 L 处理
                    break;
                case 'L':
                    x = num(tokens, i++);
                    y = num(tokens, i++);
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = num(tokens, i++);
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = num(tokens, i++);
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = num(tokens, i++), y1 = num(tokens, i++);
                    float x2 = num(tokens, i++), y2 = num(tokens, i++);
                    x = num(tokens, i++);
                    y = num(tokens, i++);
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    break;
                }
                case 'Z':
                    p.close();
                    x = sx;
                    y = sy;
                    i++;
                    break;
                default:
                    throw new IllegalArgumentException("不支持的 path 命令: " + cmd);
            }
        }
        return p;
    }

    private static float num(List<Object> tokens, int i) {
        Object o = tokens.get(i);
        if (!(o instanceof Float)) throw new IllegalArgumentException("缺少数值参数 @" + i);
        return (Float) o;
    }

    private static List<Object> tokenize(String d) {
        List<Object> out = new ArrayList<>();
        int i = 0, n = d.length();
        while (i < n) {
            char c = d.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
            } else if (Character.isLetter(c)) {
                out.add(c);
                i++;
            } else {
                int start = i;
                if (c == '-' || c == '+') i++;
                while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.')) i++;
                if (start == i) throw new IllegalArgumentException("坏数值 @" + i);
                out.add(Float.parseFloat(d.substring(start, i)));
            }
        }
        return out;
    }
}
