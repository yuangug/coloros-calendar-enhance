package com.lcodexs.coursealarm;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * 输入型底部弹窗（教务系统账号这类需要打字的场景），外观与 PickerSheet 一致：
 * 顶圆角 28dp / 居中标题 / 输入框 / 分割线 / 取消 · 确定。
 */
final class PromptSheet {
    private static final String T = CourseAlarmEntry.TAG;

    interface OnSubmit {
        void onSubmit(Map<String, String> values);
    }

    private PromptSheet() {
    }

    static void show(Context ctx, String title, List<String[]> fields,
                     Map<String, String> init, OnSubmit cb) {
        if (ctx == null) return;
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        int fg = night ? 0xFFEDEDED : 0xFF1A1A1A;
        int hint = night ? 0xFF8A8A8E : 0xFF8C8C92;
        int field = night ? 0xFF2A2A2D : 0xFFF2F2F5;
        int divider = night ? 0xFF333336 : 0xFFE6E6E9;
        int accent = night ? 0xFF7BB0FF : 0xFF1464F4;

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
        float r = PickerSheet.dp(ctx, 28);
        card.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(card);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTextColor(fg);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, PickerSheet.dp(ctx, 20), 0, PickerSheet.dp(ctx, 8));
        root.addView(tv);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(PickerSheet.dp(ctx, 20), 0, PickerSheet.dp(ctx, 20), PickerSheet.dp(ctx, 6));

        final java.util.LinkedHashMap<String, EditText> inputs = new java.util.LinkedHashMap<>();
        for (String[] f : fields) {
            String key = f[0];
            String label = f[1];
            boolean secret = f.length > 2 && "secret".equals(f[2]);

            TextView lab = new TextView(ctx);
            lab.setText(label);
            lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            lab.setTextColor(hint);
            lab.setPadding(PickerSheet.dp(ctx, 4), PickerSheet.dp(ctx, 8), 0, PickerSheet.dp(ctx, 2));
            box.addView(lab);

            EditText et = new EditText(ctx);
            et.setSingleLine(true);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            et.setTextColor(fg);
            et.setHintTextColor(hint);
            et.setHint(label);
            et.setPadding(PickerSheet.dp(ctx, 12), PickerSheet.dp(ctx, 8),
                    PickerSheet.dp(ctx, 12), PickerSheet.dp(ctx, 8));
            GradientDrawable fbg = new GradientDrawable();
            fbg.setColor(field);
            fbg.setCornerRadius(PickerSheet.dp(ctx, 10));
            et.setBackground(fbg);
            if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (init != null && init.get(key) != null) et.setText(init.get(key));
            box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inputs.put(key, et);
        }
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        android.view.View line = new android.view.View(ctx);
        line.setBackgroundColor(divider);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, PickerSheet.dp(ctx, 1))));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setTextColor(accent);
        cancel.setGravity(Gravity.CENTER);
        cancel.setMinHeight(PickerSheet.dp(ctx, 54));
        cancel.setOnClickListener(v -> d.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.view.View vline = new android.view.View(ctx);
        vline.setBackgroundColor(divider);
        buttons.addView(vline, new LinearLayout.LayoutParams(Math.max(1, PickerSheet.dp(ctx, 1)),
                PickerSheet.dp(ctx, 28)));

        TextView ok = new TextView(ctx);
        ok.setText("确定");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ok.setTextColor(accent);
        ok.setTypeface(ok.getTypeface(), android.graphics.Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setMinHeight(PickerSheet.dp(ctx, 54));
        ok.setOnClickListener(v -> {
            java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                out.put(e.getKey(), e.getValue().getText().toString().trim());
            }
            d.dismiss();
            try {
                cb.onSubmit(out);
            } catch (Throwable t) {
                Logx.e(T, "prompt submit failed", t);
            }
        });
        buttons.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        d.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        try {
            d.show();
        } catch (Throwable t) {
            Logx.e(T, "prompt show failed", t);
        }
    }
}
