package com.lcodexs.coursealarm;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 自带的底部选择器，形状与宿主 COUI 底部弹窗对齐：
 * 顶圆角 28dp、居中标题、列表项、分割线、「确定」按钮（选中项高亮用宿主强调色）。
 *
 * 只有「闹钟响铃」用它（宿主没有对应的滚轮控件）；「闹钟时间」直接用宿主自己的
 * CommonPickerDialog（见 HostPicker），所以两个选择器观感一致。
 *
 * 明暗以 Configuration.uiMode 为准；强调色取宿主 android.R.attr.colorAccent 并做可见性校验。
 */
final class PickerSheet {
    private static final String T = CourseAlarmEntry.TAG;

    interface OnPick<T> {
        void onPick(T value);
    }

    private PickerSheet() {
    }

    static void show(Context ctx, String title, List<String> options, int checkedIndex,
                     OnPick<Integer> cb) {
        if (ctx == null || options == null || options.isEmpty()) return;

        boolean night = isNight(ctx);
        int bg = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        int fg = night ? 0xFFEDEDED : 0xFF1A1A1A;
        int divider = night ? 0xFF333336 : 0xFFE6E6E9;
        int accent = accentColor(ctx, bg, night ? 0xFF7BB0FF : 0xFF1464F4);
        Logx.i(T, "picker: night=" + night + " accent=#" + Integer.toHexString(accent));

        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.dimAmount = 0.45f;
            d.getWindow().setAttributes(lp);
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card = new GradientDrawable();
        card.setColor(bg);
        float r = dp(ctx, 28);
        card.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(card);

        // 标题（居中，和 COUI 底部弹窗一致）
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTextColor(fg);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 12));
        root.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] selected = {checkedIndex};
        final boolean immediate = checkedIndex < 0;   // 动作菜单：点一项就执行
        final List<TextView> rows = new ArrayList<>();

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0);

        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            TextView row = new TextView(ctx);
            row.setText(options.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(ctx, 54));
            row.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setTextColor(i == checkedIndex ? accent : fg);
            row.setOnClickListener(v -> {
                if (immediate) {
                    d.dismiss();
                    try {
                        cb.onPick(index);
                    } catch (Throwable t) {
                        Logx.e(T, "picker callback failed", t);
                    }
                    return;
                }
                selected[0] = index;
                for (int k = 0; k < rows.size(); k++) {
                    rows.get(k).setTextColor(k == index ? accent : fg);
                }
            });
            rows.add(row);
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setPadding(0, 0, 0, dp(ctx, 6));
        root.addView(sv, svLp);

        // 分割线 + 「确定」（动作菜单不需要它：点一项就执行）
        if (!immediate) {
            View line = new View(ctx);
            line.setBackgroundColor(divider);
            root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(ctx, 1))));

            TextView ok = new TextView(ctx);
            ok.setText("确定");
            ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            ok.setTextColor(accent);
            ok.setTypeface(ok.getTypeface(), android.graphics.Typeface.BOLD);
            ok.setGravity(Gravity.CENTER);
            ok.setMinHeight(dp(ctx, 54));
            ok.setOnClickListener(v -> {
                d.dismiss();
                try {
                    cb.onPick(selected[0]);
                } catch (Throwable t) {
                    Logx.e(T, "picker callback failed", t);
                }
            });
            root.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            android.view.View spacer = new android.view.View(ctx);
            root.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ctx, 12)));
        }

        d.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        try {
            d.show();
        } catch (Throwable t) {
            Logx.e(T, "picker show failed", t);
        }
    }

    /** 回落用：宿主控件不可用时的分钟选择（宿主滚轮域：5..60，5 分钟一档） */
    static void showMinutes(Context ctx, int current, OnPick<Integer> cb) {
        List<String> opts = new ArrayList<>();
        int checked = 0;
        for (int i = HostPicker.MIN_INDEX; i <= HostPicker.MAX_INDEX; i++) {
            int minutes = HostPicker.minutesOf(i);
            opts.add(HostPicker.minutesLabel(ctx, minutes));
            if (HostPicker.indexOf(current) == i) checked = opts.size() - 1;
        }
        show(ctx, "闹钟时间", opts, checked, idx -> cb.onPick(HostPicker.minutesOf(idx + 1)));
    }

    static void showRing(Context ctx, boolean silent, OnPick<Boolean> cb) {
        List<String> opts = new ArrayList<>();
        opts.add("默认铃声");
        opts.add("静音（只振动/只亮屏提示）");
        show(ctx, "闹钟响铃", opts, silent ? 1 : 0, idx -> cb.onPick(idx == 1));
    }

    private static boolean isNight(Context ctx) {
        try {
            return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 强调色取宿主 theme 的 colorAccent；但实测 ColorOS 的 colorAccent 可能解析成
     * 近乎底色/透明度不足的值（选中项与 ✓ 于是看不见），所以必须校验，不合格就回落。
     */
    private static int accentColor(Context ctx, int bg, int fallback) {
        int c = fallback;
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                if (tv.resourceId != 0) {
                    ColorStateList csl = ctx.getResources().getColorStateList(tv.resourceId, ctx.getTheme());
                    if (csl != null) c = csl.getDefaultColor();
                } else {
                    c = tv.data;
                }
            }
        } catch (Throwable ignored) {
        }
        if (Color.alpha(c) < 200) return fallback;
        if (Math.abs(luminance(c) - luminance(bg)) < 0.18) return fallback;
        return c;
    }

    private static double luminance(int color) {
        return 0.2126 * (Color.red(color) / 255.0)
                + 0.7152 * (Color.green(color) / 255.0)
                + 0.0722 * (Color.blue(color) / 255.0);
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
