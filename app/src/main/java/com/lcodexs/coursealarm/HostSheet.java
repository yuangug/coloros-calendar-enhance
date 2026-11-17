package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 动作选择面板 —— **装在宿主自己的 COUI 底部面板里**（不是模块自绘的 Dialog）。
 *
 * 「创建课表」那个 sheet 的长相（宿主 R.layout.dialog_create_course）：
 *   COUIBottomSheetDialog(activity, R.style.COUIFitContentBottomSheetDialog)
 *   + 白底卡片 + 左上标题（sans-serif-medium / sp_18 / color_create_dialog_course_title）
 *   + 右上关闭图标（R.drawable.icon_cloose）+ 内容区
 * 这里沿用同一套：同一个 dialog 类、同一个 style、同一批 R.color / R.dimen / R.drawable，
 * 只把内容区换成「一行一项」的列表 —— 所以「导入课表 / 导出课表 / 写入方式」这些
 * 二次选择面板和原生创建课表面板是同一个观感（圆角、留白、字号、关闭键位置全一致）。
 *
 * 取不到 COUI 面板（类缺失 / style 缺失 / 构造失败）时返回 false，由调用方回落到 PickerSheet。
 */
final class HostSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_SHEET = "com.coui.appcompat.panel.COUIBottomSheetDialog";
    private static final String STYLE_SHEET = "COUIFitContentBottomSheetDialog";

    interface OnPick {
        void onPick(int index);
    }

    private static Object current;

    private HostSheet() {
    }

    /** @return true = 已用宿主 COUI 面板弹出 */
    static boolean show(Activity a, String title, List<String> items, OnPick cb) {
        if (a == null || items == null || items.isEmpty()) return false;
        ClassLoader host = a.getClassLoader();
        try {
            Class<?> cls = Ref.cls(host, CLS_SHEET);
            if (cls == null) {
                Logx.w(T, "HostSheet: 找不到 " + CLS_SHEET);
                return false;
            }
            int styleId = a.getResources().getIdentifier(STYLE_SHEET, "style", a.getPackageName());
            Object dlg = newDialog(cls, a, styleId);
            if (dlg == null) {
                Logx.w(T, "HostSheet: COUIBottomSheetDialog 构造失败 styleId=" + styleId);
                return false;
            }
            Ref.callName(dlg, "setCancelable", Boolean.TRUE);
            Ref.callName(dlg, "setContentView", content(a, host, dlg, title, items, cb));
            // 和宿主 dialog_create_course 一样的处理：不要拖拽条、不要面板把手
            Ref.callName(Ref.callName(dlg, "getBehavior"), "setDraggable", Boolean.FALSE);
            Object dll = Ref.callName(dlg, "getDragableLinearLayout");
            if (dll != null) {
                Ref.callName(Ref.callName(dll, "getDragView"), "setVisibility", View.INVISIBLE);
                Ref.callName(Ref.callName(dll, "getPanelBarView"), "setVisibility", View.INVISIBLE);
                Ref.callName(Ref.callName(dll, "getDrawLayout"), "setVisibility", View.GONE);
            }
            current = dlg;
            Ref.callName(dlg, "show");
            Logx.i(T, "HostSheet: 宿主 COUI 面板 shown title=" + title + " items=" + items.size()
                    + " styleId=" + styleId);
            return true;
        } catch (Throwable t) {
            Logx.w(T, "HostSheet: 弹宿主面板失败 " + t);
            return false;
        }
    }

    private static Object newDialog(Class<?> cls, Activity a, int styleId) {
        try {
            if (styleId != 0) {
                Constructor<?> k = Ref.ctorOf(cls, Context.class, int.class);
                if (k != null) return k.newInstance(a, styleId);
            }
            Constructor<?> k1 = Ref.ctorOf(cls, Context.class);
            if (k1 != null) return k1.newInstance(a);
            Constructor<?> k2 = Ref.ctorByArity(cls, 2);
            if (k2 != null) return k2.newInstance(a, styleId);
        } catch (Throwable t) {
            Logx.w(T, "HostSheet: new COUIBottomSheetDialog failed: " + t);
        }
        return null;
    }

    // ── 内容区（沿用对话框自身的容器，配色/字号全部取宿主资源） ──────────────

    private static View content(Activity a, ClassLoader host, Object dlg, String title,
                                List<String> items, OnPick cb) {
        final boolean night = isNight(a);
        int card = color(a, "white", night ? 0xFF1C1C1E : 0xFFFFFFFF);
        int titleColor = color(a, "color_create_dialog_course_title", night ? 0xFFEDEDED : 0xFF1A1A1A);
        int itemColor = color(a, "color_course_type_info", night ? 0xFFD0D3D9 : 0xFF3A3F47);
        int divider = night ? 0xFF2C2F35 : 0xFFF0F1F4;
        int closeTint = night ? 0xFFEDEDED : 0xFF666B73;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(card);
        int padH = dim(a, "dp_16", 16);
        int padTop = dim(a, "dp_12", 12);
        int padBottom = dim(a, "dp_24", 24);
        root.setPadding(0, padTop, 0, padBottom);

        // 标题行：左标题 + 右关闭（就是 dialog_create_course 的 tv_title / iv_close 那一行）
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(padH, 0, padH, 0);

        TextView tv = new TextView(a);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimF(a, "sp_18", 18));
        tv.setTextColor(titleColor);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView close = new ImageView(a);
        int closeRes = a.getResources().getIdentifier("icon_cloose", "drawable", a.getPackageName());
        if (closeRes != 0) {
            close.setImageResource(closeRes);
            if (close.getDrawable() != null) {
                close.getDrawable().setTint(closeTint);
            }
        } else {
            close.setImageDrawable(null);
        }
        int closeSize = dim(a, "dp_24", 24);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(closeSize, closeSize);
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> dismiss(dlg));
        head.addView(close);

        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表项
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dim(a, "dp_12", 12);
        root.addView(list, llp);

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            if (i > 0) {
                View line = new View(a);
                line.setBackgroundColor(divider);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dim(a, "dp_1", 1)));
                lp.leftMargin = padH;
                lp.rightMargin = padH;
                list.addView(line, lp);
            }
            TextView row = new TextView(a);
            row.setText(items.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimF(a, "sp_16", 16));
            row.setTextColor(itemColor);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dim(a, "dp_56", 56));
            row.setPadding(padH, 0, padH, 0);
            GradientDrawable ripple = new GradientDrawable();
            ripple.setColor(0x00000000);
            row.setBackground(ripple);
            row.setOnClickListener(v -> {
                dismiss(dlg);
                try {
                    cb.onPick(idx);
                } catch (Throwable t) {
                    Logx.e(T, "HostSheet: 回调失败", t);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 条目多（比如下载目录里一堆 .ics/.xlsx）时必须限高，否则内容比屏幕还高、
        // 面板把列表顶到屏幕外面，用户只能看到标题（实测踩到）。只有真超了才限。
        final ScrollView fsv = sv;
        sv.post(() -> {
            try {
                if (fsv.getChildCount() < 1) return;
                int contentH = fsv.getChildAt(0).getHeight();
                int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
                if (contentH > maxH) {
                    ViewGroup.LayoutParams lp = fsv.getLayoutParams();
                    lp.height = maxH;
                    fsv.setLayoutParams(lp);
                    fsv.requestLayout();
                    Logx.i(T, "HostSheet: 内容 " + contentH + " > " + maxH + "，已限高可滚动");
                }
            } catch (Throwable t) {
                Logx.w(T, "HostSheet 限高失败: " + t);
            }
        });
        return sv;
    }

    static void dismiss(Object dlg) {
        try {
            Method m = Ref.methodByArity(dlg.getClass(), "dismiss", 0);
            if (m != null) m.invoke(dlg);
        } catch (Throwable ignored) {
        }
        if (dlg == current) current = null;
    }

    private static boolean isNight(Context ctx) {
        try {
            return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int color(Activity a, String name, int fallback) {
        try {
            int id = a.getResources().getIdentifier(name, "color", a.getPackageName());
            if (id != 0) return a.getResources().getColor(id, a.getTheme());
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int dim(Activity a, String name, int fallbackDp) {
        try {
            int id = a.getResources().getIdentifier(name, "dimen", a.getPackageName());
            if (id != 0) return a.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return PickerSheet.dp(a, fallbackDp);
    }

    /** sp_18 / sp_16 在宿主里是 dimen（sp 值），取出来是 px，直接当 COMPLEX_UNIT_PX 用 */
    private static float dimF(Activity a, String name, float fallbackSp) {
        try {
            int id = a.getResources().getIdentifier(name, "dimen", a.getPackageName());
            if (id != 0) return a.getResources().getDimension(id);
        } catch (Throwable ignored) {
        }
        return fallbackSp * a.getResources().getDisplayMetrics().scaledDensity;
    }
}
