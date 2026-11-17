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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 课表主题面板。
 *
 * <p>第一优先：**宿主自己的** {@code com.coui.appcompat.panel.COUIBottomSheetDialog}
 * （ColorOS 17 的底部面板：自带毛玻璃底、大圆角、跟手拖拽、面板进出场动效、身后的原生遮罩）。
 * 宿主「创建课表 / 查看周表」弹的就是这一族面板，所以样式天然一致 ——
 * 之前手搓 Dialog（自带白底 + setDimAmount(0.35)）才是「和日历原生界面不符、遮罩改了没反应」的根因。
 *
 * <p>取不到 COUI 面板时，退回自绘底部 sheet（老实现），功能不受影响。
 *
 * <p>内容区只用宿主 token（couiColorLabelPrimary / LabelSecondary / Primary / FillThin），
 * 不再写死白色和蓝色；明暗跟随 {@code Configuration.uiMode}。
 */
final class ThemeSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private final Activity a;
    private final ClassLoader host;
    private boolean night;
    private int fg, sub, fill, accent, hint;

    /** 原生面板（COUIBottomSheetDialog）或兜底 Dialog，二选一 */
    private Object sheet;
    private Dialog dlg;
    private LinearLayout content;

    private ThemeSheet(Activity a) {
        this.a = a;
        this.host = a.getClassLoader();
    }

    static void show(Activity a) {
        if (a == null) return;
        try {
            new ThemeSheet(a).open();
        } catch (Throwable t) {
            Logx.e(T, "theme sheet 打开失败", t);
            CurriculumIo.toast(a, "主题面板打不开：" + t);
        }
    }

    private void open() {
        night = isNight(a);
        resolveTokens();
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(4), pad, dp(20));

        if (!nativeSheet()) legacySheet();
        buildMain();
    }

    // ── 面板本体 ─────────────────────────────────────────────────────────────

    /** 宿主原生底部面板；类/构造器/方法任何一步缺失都返回 false 走兜底 */
    private boolean nativeSheet() {
        try {
            Class<?> cls = Ref.clsStrict(host, "com.coui.appcompat.panel.COUIBottomSheetDialog");
            int style = a.getResources().getIdentifier("DefaultBottomSheetDialog", "style", a.getPackageName());
            Object d = style != 0
                    ? cls.getConstructor(Context.class, int.class).newInstance(a, style)
                    : cls.getConstructor(Context.class).newInstance(a);
            Ref.callName(d, "setContentView", content);
            quiet(d, "setBackgroundBlur", Boolean.TRUE);      // 面板毛玻璃
            quiet(d, "setCanPullUp", Boolean.FALSE);
            quiet(d, "setDraggable", Boolean.FALSE);          // 内容里有横滑色卡，别跟手
            quiet(d, "setCanceledOnTouchOutside", Boolean.TRUE);
            Ref.callName(d, "show");
            sheet = d;
            Logx.i(T, "theme sheet: 宿主原生 COUIBottomSheetDialog (style="
                    + Integer.toHexString(style) + ")");
            return true;
        } catch (Throwable t) {
            Logx.w(T, "COUIBottomSheetDialog 不可用，回退自绘 sheet: " + t);
            return false;
        }
    }

    /** 兜底：老的自绘底部 sheet（非 ColorOS 宿主时才走到） */
    private void legacySheet() {
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.35f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.BOTTOM);
        }
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF1C1F25 : 0xFFFFFFFF);
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        wrap.setBackground(bg);
        wrap.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(wrap, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setOnShowListener(d -> {
            Window ww = dlg.getWindow();
            if (ww != null) {
                ww.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dlg.show();
        Logx.i(T, "theme sheet: 自绘兜底 sheet");
    }

    private void dismiss() {
        try {
            if (sheet != null) {
                Ref.callName(sheet, "dismiss");
            } else if (dlg != null) {
                dlg.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    // ── 一级：背景类型 + 遮罩 + 课程卡片 ──────────────────────────────────────

    private void buildMain() {
        content.removeAllViews();
        content.addView(header("课表主题", () -> dismiss()));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);

        int cur = ThemeStore.type(a);
        row.addView(tile("无背景", 0, cur == ThemeStore.TYPE_NONE, v -> {
            ThemeStore.setType(a, ThemeStore.TYPE_NONE);
            ThemeBg.apply(a);
            dismiss();
        }), cellLp());
        row.addView(tile("纯色", 1, cur == ThemeStore.TYPE_SOLID, v -> buildSolid()), cellLp());
        row.addView(tile("渐变", 2, cur == ThemeStore.TYPE_GRADIENT, v -> buildGradient()), cellLp());
        row.addView(tile("图片", 3, cur == ThemeStore.TYPE_IMAGE, v -> {
            ThemeBg.pick(a);
            dismiss();
        }), cellLp());
        content.addView(row);

        // 背景遮罩
        content.addView(sectionLabel("背景遮罩", dp(20)));
        LinearLayout scrimRow = new LinearLayout(a);
        scrimRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        scrimRow.setLayoutParams(slp);
        int curScrim = ThemeStore.scrim(a);
        int[] levels = {0, 60, 120, 190};
        String[] names = {"透明", "浅", "中", "深"};
        for (int i = 0; i < names.length; i++) {
            final int value = levels[i];
            boolean on = Math.abs(curScrim - value) < 20;
            TextView chip = chip(names[i], on);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(34));
            lp.rightMargin = dp(10);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                ThemeStore.setScrim(a, value);
                ThemeBg.apply(a);
                buildMain();
                Logx.i(T, "背景遮罩 → " + value);
            });
            scrimRow.addView(chip);
        }
        content.addView(scrimRow);
        if (cur == ThemeStore.TYPE_NONE) {
            // 老实说明：没有背景层时遮罩无处可盖（之前的实现是默默无效，用户只会觉得坏了）
            content.addView(hintText("当前是「无背景」，遮罩没有可盖的背景层；选了纯色/渐变/图片才会生效"));
        }

        // 课程卡片（经典 / 凝光 / 液态玻璃）
        content.addView(sectionLabel("课程卡片", dp(18)));
        LinearLayout ngRow = new LinearLayout(a);
        ngRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        ngRow.setLayoutParams(nlp);
        int curStyle = ThemeStore.style(a);
        String[] styles = {"经典", "凝光", "液态玻璃"};
        int[] styleValues = {ThemeStore.STYLE_CLASSIC, ThemeStore.STYLE_NINGGUANG, ThemeStore.STYLE_LIQUID};
        int[] widths = {64, 64, 96};
        for (int i = 0; i < styles.length; i++) {
            final int value = styleValues[i];
            TextView chip = chip(styles[i], curStyle == value);
            chip.setLayoutParams(chipLp(widths[i]));
            chip.setOnClickListener(v -> {
                ThemeStore.setStyle(a, value);
                HostTablePage.repaint();
                buildMain();
                Logx.i(T, "课程卡样式 → " + styles[value] + " (" + NingGuang.describe(a) + ")");
            });
            ngRow.addView(chip);
        }
        content.addView(ngRow);

        NingGuang.Tokens tk = NingGuang.tokens(a);
        String hint;
        if (curStyle == ThemeStore.STYLE_LIQUID) {
            hint = "液态玻璃：背景按 SDF 法线位移重采样（真折射）+ 边缘色散 + 一圈镜面高光 + 内侧厚度阴影"
                    + "（AGSL · 每帧现算，采样源＝页面真实背景层）";
        } else {
            hint = "凝光：磨砂模糊 + 材质混色 + 内晕 + 轮廓光，材质参数直接取宿主底栏那一档（"
                    + NativeMaterial.dump(NativeMaterial.get(a)) + "）";
        }
        content.addView(hintText(hint));
    }

    // ── 二级：纯色 / 渐变 ────────────────────────────────────────────────────

    private void buildSolid() {
        content.removeAllViews();
        content.addView(header("选择底色", this::buildMain));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        row.setLayoutParams(rlp);

        int curType = ThemeStore.type(a);
        int current = ThemeStore.solidIndex(a);
        for (int i = 0; i < ThemeStore.SOLIDS.length; i++) {
            final int idx = i;
            boolean on = idx == current && curType == ThemeStore.TYPE_SOLID;
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ThemeStore.SOLIDS[i]);
            g.setStroke(dp(on ? 3 : 1), on ? accent : 0x33808080);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(10);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                ThemeStore.setSolid(a, idx);
                ThemeBg.apply(a);
                dismiss();
            });
            row.addView(sw);
        }
        content.addView(row);
        content.addView(hintText("纯色底 + 半透明格子：底面会从课表格子里透出来"));
    }

    private void buildGradient() {
        content.removeAllViews();
        content.addView(header("选择渐变", this::buildMain));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        row.setLayoutParams(rlp);

        int curType = ThemeStore.type(a);
        int current = ThemeStore.gradientIndex(a);
        for (int i = 0; i < ThemeStore.GRADIENTS.length; i++) {
            final int idx = i;
            boolean on = idx == current && curType == ThemeStore.TYPE_GRADIENT;
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    ThemeStore.GRADIENTS[i]);
            g.setCornerRadius(dp(14));
            g.setStroke(dp(on ? 3 : 1), on ? accent : 0x33808080);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62), dp(62));
            lp.rightMargin = dp(12);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                ThemeStore.setGradient(a, idx);
                ThemeBg.apply(a);
                dismiss();
            });
            row.addView(sw);
        }
        content.addView(row);
        content.addView(hintText("渐变底 + 半透明格子：底面会从课表格子里透出来"));
    }

    // ── 小部件 ───────────────────────────────────────────────────────────────

    private View header(String title, Runnable onClose) {
        FrameLayout head = new FrameLayout(a);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView close = new TextView(a);
        close.setText("取消");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setTextColor(sub);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(60), dp(34));
        clp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> onClose.run());
        head.addView(close);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        t.setLayoutParams(tlp);
        head.addView(t);
        return head;
    }

    private TextView sectionLabel(String text, int topMargin) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hintText(String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(hint);
        tv.setAlpha(0.75f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams chipLp(int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(34));
        lp.rightMargin = dp(10);
        return lp;
    }

    /** 一个卡片：圆角方块图标 + 下方文字（图标自绘，不依赖 drawable 资源） */
    private LinearLayout tile(String label, int iconKind, boolean selected, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClickable(true);
        box.setFocusable(true);
        box.setBackground(ripple(dp(20)));

        FrameLayout frame = new FrameLayout(a);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(fill);
        tb.setCornerRadius(dp(18));
        if (selected) tb.setStroke(dp(2), accent);
        frame.setBackground(tb);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(72), dp(72));
        frame.setLayoutParams(tlp);

        IconView iv = new IconView(a, iconKind, fg);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(32), dp(32));
        ilp.gravity = Gravity.CENTER;
        frame.addView(iv, ilp);
        box.addView(frame);

        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(selected ? accent : fg);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(tv, lp);

        box.setOnClickListener(onClick);
        return box;
    }

    private TextView chip(String text, boolean on) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? withAlpha(accent, 0x26) : fill);
        g.setCornerRadius(dp(17));
        if (on) g.setStroke(dp(2), accent);
        tv.setBackground(g);
        tv.setTextColor(on ? accent : fg);
        return tv;
    }

    /** 宿主 listChoiceBackgroundIndicator 的点击反馈（拿不到就用透明） */
    private android.graphics.drawable.Drawable ripple(int radius) {
        try {
            int id = a.getResources().getIdentifier("listChoiceBackgroundIndicator", "attr", "android");
            TypedValue tv = new TypedValue();
            if (id != 0 && a.getTheme().resolveAttribute(id, tv, true) && tv.resourceId != 0) {
                return a.getDrawable(tv.resourceId);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── token ────────────────────────────────────────────────────────────────

    private void resolveTokens() {
        fg = attrColor("couiColorLabelPrimary", night ? 0xFFF0F1F3 : 0xFF1A1A1A);
        sub = attrColor("couiColorLabelSecondary", night ? 0xFF9AA0AB : 0xFF808690);
        accent = attrColor("couiColorPrimary", accentFallback());
        fill = attrColor("couiColorFillThin", night ? 0x1AFFFFFF : 0x0F000000);
        if (((fill >>> 24) & 0xFF) > 0x40) fill = withAlpha(fill, 0x14);   // 不要把 token 当实心块
        hint = sub;
    }

    private int attrColor(String name, int def) {
        try {
            int id = a.getResources().getIdentifier(name, "attr", a.getPackageName());
            if (id != 0) {
                TypedValue tv = new TypedValue();
                if (a.getTheme().resolveAttribute(id, tv, true)
                        && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    int col = tv.data;
                    if (((col >>> 24) & 0xFF) == 0) col |= 0xFF000000;
                    return col;
                }
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private int accentFallback() {
        try {
            TypedValue tv = new TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                int col = tv.data | 0xFF000000;
                int lum = ((col >> 16 & 0xFF) * 299 + (col >> 8 & 0xFF) * 587 + (col & 0xFF) * 114) / 1000;
                if (lum > 40 && lum < 230) return col;
            }
        } catch (Throwable ignored) {
        }
        return 0xFF3A7AFF;
    }

    private static int withAlpha(int color, int a) {
        return ((a & 0xFF) << 24) | (color & 0x00FFFFFF);
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void quiet(Object target, String name, Object... args) {
        try {
            Ref.callName(target, name, args);
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** 卡片图标：0 无 / 1 纯色 / 2 渐变 / 3 图片，全部 Canvas 画 */
    private static final class IconView extends View {
        private final int kind;
        private final int ink;
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
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
