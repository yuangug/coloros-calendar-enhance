package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 课表主题面板：底部卡片 sheet（照宿主「创建课表」那种样式）。
 *
 *   ┌──────────────────────────────┐
 *   │ (取消)       课表主题          │
 *   │  ┌────┐  ┌────┐  ┌────┐  ┌────┐│
 *   │  │无  │  │纯色│  │渐变│  │图片││
 *   │  └────┘  └────┘  └────┘  └────┘│
 *   │        遮罩  (浅)(中)(深)       │
 *   └──────────────────────────────┘
 *
 * 二级面板（纯色/渐变）在同一张 sheet 里就地替换内容，不再叠一层弹窗。
 */
final class ThemeSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private ThemeSheet() {
    }

    static void show(Activity a) {
        if (a == null) return;
        final boolean night = isNight(a);
        final int bgColor = night ? 0xFF1C1F25 : 0xFFFFFFFF;
        final int cardColor = night ? 0xFF2A2E36 : 0xFFF2F3F7;
        final int fg = night ? 0xFFF0F1F3 : 0xFF1A1A1A;
        final int sub = night ? 0xFF9AA0AB : 0xFF808690;
        final int accent = accentColor(a);

        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.35f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.BOTTOM);
        }

        final LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadii(new float[]{dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 24), 0, 0, 0, 0});
        sheet.setBackground(bg);
        int pad = dp(a, 20);
        sheet.setPadding(pad, dp(a, 12), pad, dp(a, 20) + navBar(a));

        // ── 标题行：(取消) 居中标题
        FrameLayout head = new FrameLayout(a);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44)));
        TextView cancel = new TextView(a);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cancel.setTextColor(fg);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(cardColor);
        cbg.setCornerRadius(dp(a, 18));
        cancel.setBackground(cbg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(a, 68), dp(a, 34));
        clp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> dlg.dismiss());
        head.addView(cancel);

        TextView title = new TextView(a);
        title.setText("课表主题");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(fg);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        title.setLayoutParams(tlp);
        head.addView(title);
        sheet.addView(head);

        // ── 内容区（一级/二级共用）
        final ScrollView scroll = new ScrollView(a);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(scroll);

        final LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(a, 8), 0, 0);
        scroll.addView(content);

        dlg.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildMain(a, dlg, content, cardColor, fg, sub, accent, night);
        dlg.setOnShowListener(d -> {
            Window ww = dlg.getWindow();
            if (ww != null) {
                ww.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dlg.show();
        Logx.i(T, "theme sheet shown night=" + night);
    }

    // ── 一级：四个卡片 ──────────────────────────────────────────────────────

    private static void buildMain(Activity a, Dialog dlg, LinearLayout content,
                                  int cardColor, int fg, int sub, int accent, boolean night) {
        content.removeAllViews();
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(a, 10);
        row.setLayoutParams(rlp);

        int cur = ThemeStore.type(a);
        row.addView(card(a, "无背景", 0,
                cur == ThemeStore.TYPE_NONE, cardColor, fg, sub, accent, v -> {
                    ThemeStore.setType(a, ThemeStore.TYPE_NONE);
                    ThemeBg.apply(a);
                    dlg.dismiss();
                }), cellLp(a));

        row.addView(card(a, "纯色", 1,
                cur == ThemeStore.TYPE_SOLID, cardColor, fg, sub, accent,
                v -> buildSolid(a, dlg, content, cur, cardColor, fg, sub, accent)), cellLp(a));

        row.addView(card(a, "渐变", 2,
                cur == ThemeStore.TYPE_GRADIENT, cardColor, fg, sub, accent,
                v -> buildGradient(a, dlg, content, cur, cardColor, fg, sub, accent)), cellLp(a));

        row.addView(card(a, "图片", 3,
                cur == ThemeStore.TYPE_IMAGE, cardColor, fg, sub, accent, v -> {
                    ThemeBg.pick(a);
                    dlg.dismiss();
                }), cellLp(a));

        content.addView(row);

        // 遮罩档位（图片模式下影响可读性）
        TextView label = new TextView(a);
        label.setText("背景遮罩");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(sub);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(a, 20);
        label.setLayoutParams(llp);
        content.addView(label);

        LinearLayout scrim = new LinearLayout(a);
        scrim.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(a, 8);
        scrim.setLayoutParams(slp);
        int curScrim = ThemeStore.scrim(a);
        int[][] levels = {{60, 0}, {120, 1}, {190, 2}};
        String[] names = {"浅", "中", "深"};
        for (int i = 0; i < names.length; i++) {
            final int value = levels[i][0];
            boolean on = Math.abs(curScrim - value) < 20;
            TextView chip = chip(a, names[i], on, cardColor, fg, accent);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(a, 64), dp(a, 34));
            lp.rightMargin = dp(a, 10);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                ThemeStore.setScrim(a, value);
                ThemeBg.apply(a);
                buildMain(a, dlg, content, cardColor, fg, sub, accent, false);
            });
            scrim.addView(chip);
        }
        content.addView(scrim);
    }

    // ── 二级：纯色 / 渐变 ──────────────────────────────────────────────────

    private static void buildSolid(Activity a, Dialog dlg, LinearLayout content, int curType,
                                   int cardColor, int fg, int sub, int accent) {
        content.removeAllViews();
        content.addView(subHeader(a, "选择底色", fg, sub, v -> {
            buildMain(a, dlg, content, cardColor, fg, sub, accent, false);
        }));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(a, 12);
        row.setLayoutParams(rlp);

        int current = ThemeStore.solidIndex(a);
        for (int i = 0; i < ThemeStore.SOLIDS.length; i++) {
            final int idx = i;
            int color = ThemeStore.SOLIDS[i];
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            g.setStroke(dp(a, idx == current && curType == ThemeStore.TYPE_SOLID ? 3 : 1),
                    idx == current && curType == ThemeStore.TYPE_SOLID ? accent : 0x33808080);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
            lp.rightMargin = dp(a, 10);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                ThemeStore.setSolid(a, idx);
                ThemeBg.apply(a);
                dlg.dismiss();
            });
            row.addView(sw);
        }
        content.addView(row);
    }

    private static void buildGradient(Activity a, Dialog dlg, LinearLayout content, int curType,
                                      int cardColor, int fg, int sub, int accent) {
        content.removeAllViews();
        content.addView(subHeader(a, "选择渐变", fg, sub, v -> {
            buildMain(a, dlg, content, cardColor, fg, sub, accent, false);
        }));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(a, 12);
        row.setLayoutParams(rlp);

        int current = ThemeStore.gradientIndex(a);
        for (int i = 0; i < ThemeStore.GRADIENTS.length; i++) {
            final int idx = i;
            int[] ab = ThemeStore.GRADIENTS[i];
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, ab);
            g.setCornerRadius(dp(a, 12));
            g.setStroke(dp(a, idx == current && curType == ThemeStore.TYPE_GRADIENT ? 3 : 1),
                    idx == current && curType == ThemeStore.TYPE_GRADIENT ? accent : 0x33808080);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 62), dp(a, 62));
            lp.rightMargin = dp(a, 12);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                ThemeStore.setGradient(a, idx);
                ThemeBg.apply(a);
                dlg.dismiss();
            });
            row.addView(sw);
        }
        content.addView(row);
    }

    private static View subHeader(Activity a, String text, int fg, int sub,
                                 View.OnClickListener back) {
        FrameLayout head = new FrameLayout(a);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(fg);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        head.addView(t, tlp);

        TextView backBtn = new TextView(a);
        backBtn.setText("返回");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        backBtn.setTextColor(sub);
        backBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(a, 60), dp(a, 34));
        blp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        head.addView(backBtn, blp);
        backBtn.setOnClickListener(back);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44)));
        return head;
    }

    // ── 小部件 ────────────────────────────────────────────────────────────

    private static LinearLayout.LayoutParams cellLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(a, 4);
        lp.rightMargin = dp(a, 4);
        return lp;
    }

    /** 一个卡片：圆角方块图标 + 下方文字（图标自绘，不依赖 drawable 资源） */
    private static LinearLayout card(Activity a, String label, int iconKind, boolean selected,
                                     int cardColor, int fg, int sub, int accent,
                                     View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClickable(true);
        box.setFocusable(true);

        FrameLayout tile = new FrameLayout(a);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(cardColor);
        tb.setCornerRadius(dp(a, 18));
        if (selected) tb.setStroke(dp(a, 2), accent);
        tile.setBackground(tb);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(a, 72), dp(a, 72));
        tile.setLayoutParams(tlp);

        IconView iv = new IconView(a, iconKind, fg);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(a, 32), dp(a, 32));
        ilp.gravity = Gravity.CENTER;
        tile.addView(iv, ilp);
        box.addView(tile);

        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(selected ? accent : fg);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 8);
        box.addView(tv, lp);

        box.setOnClickListener(onClick);
        return box;
    }

    private static TextView chip(Activity a, String text, boolean on,
                                 int cardColor, int fg, int accent) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? (accent & 0x00FFFFFF) | 0x22000000 : cardColor);
        g.setCornerRadius(dp(a, 17));
        if (on) g.setStroke(dp(a, 2), accent);
        tv.setBackground(g);
        tv.setTextColor(on ? accent : fg);
        return tv;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 宿主强调色（取不到就用固定蓝） */
    private static int accentColor(Context c) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                int col = tv.data;
                int a = (col >>> 24) & 0xFF;
                if (a < 200) col |= 0xFF000000;
                // 太亮/太暗都回落，保证可见
                int lum = ((col >> 16 & 0xFF) * 299 + (col >> 8 & 0xFF) * 587 + (col & 0xFF) * 114) / 1000;
                if (lum < 40 || lum > 230) return 0xFF3A7AFF;
                return col;
            }
        } catch (Throwable ignored) {
        }
        return 0xFF3A7AFF;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int navBar(Context c) {
        try {
            int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(c, 16);
    }

    /** 卡片图标：0 无 / 1 纯色 / 2 渐变 / 3 图片，全部 Canvas 画 */
    private static final class IconView extends View {
        private final int kind;
        private final int ink;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        IconView(Context c, int kind, int ink) {
            super(c);
            this.kind = kind;
            this.ink = ink;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            float pad = s * 0.14f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.075f);
            p.setColor(0xFF8C9199);
            switch (kind) {
                case 0:   // 无：方框 + 斜线
                    r.set(pad, pad, w - pad, h - pad);
                    canvas.drawRect(r, p);
                    canvas.drawLine(pad, h - pad, w - pad, pad, p);
                    break;
                case 1: {  // 纯色：三个圆
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    float rad = s * 0.13f;
                    p.setColor(0xFF6BA5FF);
                    canvas.drawCircle(w / 2f, h * 0.30f, rad, p);
                    p.setColor(0xFFFFA878);
                    canvas.drawCircle(w * 0.30f, h * 0.70f, rad, p);
                    p.setColor(0xFF96D6AA);
                    canvas.drawCircle(w * 0.70f, h * 0.70f, rad, p);
                    break;
                }
                case 2: {  // 渐变：竖向渐变方块
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setShader(new android.graphics.LinearGradient(0, pad, 0, h - pad,
                            0xFF78AAFF, 0xFFC496FF, android.graphics.Shader.TileMode.CLAMP));
                    r.set(pad, pad, w - pad, h - pad);
                    canvas.drawRoundRect(r, s * 0.12f, s * 0.12f, p);
                    p.setShader(null);
                    break;
                }
                default: { // 图片：相框 + 太阳 + 山
                    r.set(pad, pad, w - pad, h - pad);
                    canvas.drawRect(r, p);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(ink);
                    canvas.drawCircle(w * 0.34f, h * 0.34f, s * 0.055f, p);
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(pad + s * 0.02f, h - pad - s * 0.02f);
                    path.lineTo(w * 0.42f, h * 0.52f);
                    path.lineTo(w * 0.62f, h - pad - s * 0.02f);
                    path.close();
                    canvas.drawPath(path, p);
                    path.reset();
                    path.moveTo(w * 0.52f, h - pad - s * 0.02f);
                    path.lineTo(w * 0.70f, h * 0.42f);
                    path.lineTo(w - pad - s * 0.02f, h - pad - s * 0.02f);
                    path.close();
                    canvas.drawPath(path, p);
                    break;
                }
            }
        }
    }
}
