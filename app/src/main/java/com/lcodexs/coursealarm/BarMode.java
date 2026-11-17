package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Method;

/**
 * 底栏左右两个圆钮的「按页面独立」逻辑。
 *
 *   日历 / 待办页：保持宿主原样 —— 左＝视图菜单，右＝新建（本类 active=false 时完全不介入）
 *   课程表页    ：左＝课表页的周次选择（原顶栏第 1 个按钮的位置），右＝课表页的 ⋮ 菜单
 *
 * 不改宿主 listener：点击在 {@code View#performClick} 处拦（见 Hooks/MainTabHook），
 * 所以离开课表页时只要 active=false，宿主原有的点击行为原样生效。
 */
final class BarMode {
    private static final String T = CourseAlarmEntry.TAG;

    /** 是否处在课程表页（决定点击归属） */
    static volatile boolean active = false;

    private static Drawable savedLeft;
    private static Drawable savedRight;
    private static java.lang.ref.WeakReference<Activity> owner = new java.lang.ref.WeakReference<>(null);

    private BarMode() {
    }

    static void enter(Activity a) {
        if (active && owner.get() == a) {
            syncAccessories(a);
            setWindowBackdrop(a, true);
            selectSegment(a, 2);
            return;
        }
        Activity previous = owner.get();
        if (active && previous != null && previous != a) exit(previous);
        owner = new java.lang.ref.WeakReference<>(a);
        active = true;
        if (MainTabHook.newBar) {
            enterNew(a);
            return;
        }
        // 左＝课表自己的视图按钮（宿主那两个圆角矩形图标），右＝课表 ⋮
        swapIcon(a, "float_view_menu_button", "menu_mouth_handoff_default_icon_v2", true);
        swapIcon(a, "float_add_button", "table_action_more", false);
        setBarBackdrop(a, true);
        setWindowBackdrop(a, true);
        hardenButtons(a, true);
        selectSegment(a, 2);
    }

    static void exit(Activity a) {
        active = false;
        owner.clear();
        if (MainTabHook.newBar) {
            exitNew(a);
            return;
        }
        restoreIcon(a, "float_view_menu_button", true);
        restoreIcon(a, "float_add_button", false);
        setBarBackdrop(a, false);
        setWindowBackdrop(a, false);
        hardenButtons(a, false);
    }

    // ── 17.x 新底栏（COUIBottomFloatingAccessoryNavigationBar） ───────────────

    private static android.graphics.drawable.Drawable savedStartIcon, savedEndIcon;
    private static Drawable tableStartIcon, tableEndIcon;

    /** Native Fragment initialization can overwrite the icons after onResume. */
    static void syncAccessories(Activity activity) {
        if (!active || !MainTabHook.newBar) return;
        Object bar = NewBar.barOf(activity);
        if (bar == null) return;
        Drawable start = NewBar.accessoryIcon(bar, true), end = NewBar.accessoryIcon(bar, false);
        if (tableStartIcon == null || start != tableStartIcon) {
            savedStartIcon = NewBar.swapAccessory(bar, true, activity, "ic_for_week_24");
            tableStartIcon = NewBar.accessoryIcon(bar, true);
        }
        if (tableEndIcon == null || end != tableEndIcon) {
            savedEndIcon = NewBar.swapAccessory(bar, false, activity, "table_action_more");
            tableEndIcon = NewBar.accessoryIcon(bar, false);
        }
    }

    /**
     * 新底栏没有 float_view_menu_button / float_add_button，取而代之的是左右两个「附件」圆钮：
     *   左（start accessory）= 视图切换，右（end accessory）= 新建日程
     * 课表页把它们换成 周次选择 / 课表 ⋮，点击由 {@link NewBar} 包的 listener 接管
     * （BarMode.active 为 true 时才改道，日历/待办页一点不受影响）。
     */
    private static void enterNew(Activity a) {
        Object bar = NewBar.barOf(a);
        if (bar == null) {
            Logx.w(T, "新底栏控件不存在，课表页按钮不改");
            return;
        }
        savedStartIcon = NewBar.swapAccessory(bar, true, a, "ic_for_week_24");
        savedEndIcon = NewBar.swapAccessory(bar, false, a, "table_action_more");
        tableStartIcon = NewBar.accessoryIcon(bar, true);
        tableEndIcon = NewBar.accessoryIcon(bar, false);
        setBarBackdrop(a, true);
        setWindowBackdrop(a, true);
        selectSegment(a, 2);
    }

    private static void exitNew(Activity a) {
        Object bar = NewBar.barOf(a);
        if (bar != null) {
            NewBar.restoreAccessory(bar, true, savedStartIcon);
            NewBar.restoreAccessory(bar, false, savedEndIcon);
            savedStartIcon = null;
            savedEndIcon = null;
            tableStartIcon = null;
            tableEndIcon = null;
        }
        setBarBackdrop(a, false);
        setWindowBackdrop(a, false);
    }

    /** 主题在课表页里被改过：把顶部留白条同步刷一遍（底栏底衬是恒透明的，不用重刷） */
    static void refreshBackdrop(Activity a) {
        if (!active || a == null) return;
        setWindowBackdrop(a, true);
    }

    // ── 圆钮底色 ────────────────────────────────────────────────────────────

    private static android.graphics.drawable.Drawable savedLeftBg, savedRightBg;

    /**
     * 两个圆钮本身是 BlurButtonWrapperView（内部 InnerShadowView 画的半透明白圈），
     * 底栏底衬透明以后，白圈会跟着底下的背景图染色 —— 原生界面上它是不透的白。
     * 课表页把它们套一层实心白圆底，离开时把原背景还回去。
     */
    private static void hardenButtons(Activity a, boolean on) {
        harden(a, "float_view_menu_button", on, true);
        harden(a, "float_add_button", on, false);
    }

    private static void harden(Activity a, String idName, boolean on, boolean left) {
        View v = a.findViewById(id(a, idName));
        if (v == null) return;
        if (on) {
            if (left) {
                if (savedLeftBg == null) savedLeftBg = v.getBackground();
            } else {
                if (savedRightBg == null) savedRightBg = v.getBackground();
            }
            v.setBackground(whiteCircle(a));
            Logx.i(T, "圆钮实心白底 → " + idName);
        } else {
            android.graphics.drawable.Drawable d = left ? savedLeftBg : savedRightBg;
            v.setBackground(d);
            if (left) savedLeftBg = null; else savedRightBg = null;
            Logx.i(T, "圆钮底色已还原 " + idName);
        }
    }

    private static android.graphics.drawable.Drawable whiteCircle(Activity a) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFFFFFFFF);
        return g;
    }

    // ── 顶部留白条 ──────────────────────────────────────────────────────────

    /**
     * appbar 收起后，ll_main 顶部那条（含状态栏下面）露出的是窗口的 base_background_color，
     * 主题是深色或图片时就是一道白边。这里把主题铺成 Drawable 套到 ll_main 上，离开时清掉
     * （宿主自己没给 ll_main 设背景，所以 setBackground(null) 就是原样）。
     */
    private static void setWindowBackdrop(Activity a, boolean on) {
        View main = a.findViewById(id(a, "ll_main"));
        if (main == null) {
            Logx.w(T, "ll_main 不存在");
            return;
        }
        if (on) {
            android.graphics.drawable.Drawable d = ThemeBg.windowDrawable(main);
            if (d == null) {
                main.setBackground(null);
                Logx.i(T, "顶部留白条：没有主题，保持原样");
                return;
            }
            main.setBackground(d);
            Logx.i(T, "顶部留白条 → 主题背景");
        } else {
            main.setBackground(null);
            Logx.i(T, "顶部留白条已还原");
        }
    }

    // ── 底栏底衬 ────────────────────────────────────────────────────────────

    private static android.graphics.drawable.Drawable savedBarBg;

    /**
     * 底栏自己有一条 @color/floating_button_bg_color 的实心底衬（bottom_blur_view）。
     * 课表页设了背景图/底色时，这条白底会在页面底部留一条白边，所以进页时把它调透明，
     * 离开时把原来的 Drawable 还回去（不重建，不丢宿主自己的毛玻璃样式）。
     */
    private static void setBarBackdrop(Activity a, boolean transparent) {
        View v = a.findViewById(id(a, "bottom_blur_view"));
        if (v == null) {
            Logx.w(T, "bottom_blur_view 不存在");
            return;
        }
        if (transparent) {
            if (savedBarBg == null) savedBarBg = v.getBackground();
            v.setBackgroundColor(0x00000000);
            Logx.i(T, "底栏底衬 → 透明（露出课表主题）");
        } else if (savedBarBg != null) {
            v.setBackground(savedBarBg);
            Logx.i(T, "底栏底衬已还原");
        }
    }

    /**
     * performClick 处调用：返回 true 表示「这个点击归课表页」，宿主 listener 不再执行。
     */
    static boolean handleClick(View v) {
        if (!active || v == null) return false;
        Context c = v.getContext();
        if (c == null) return false;
        int left = id(c, "float_view_menu_button");
        int right = id(c, "float_add_button");
        if (left != 0 && v.getId() == left) {
            Logx.i(T, "bottom left → 课表周次选择");
            HostTablePage.showWeekPicker(v);
            return true;
        }
        if (right != 0 && v.getId() == right) {
            Logx.i(T, "bottom right → 课表 ⋮ 菜单");
            HostTablePage.showMenu(v);
            return true;
        }
        return false;
    }

    // ── 图标 ───────────────────────────────────────────────────────────────

    private static void swapIcon(Activity a, String viewIdName, String drawableName, boolean left) {
        View wrapper = a.findViewById(id(a, viewIdName));
        if (wrapper == null) {
            Logx.w(T, "bottom button 不存在: " + viewIdName);
            return;
        }
        int res = a.getResources().getIdentifier(drawableName, "drawable", a.getPackageName());
        if (res == 0) {
            Logx.w(T, "drawable 不存在: " + drawableName);
            return;
        }
        ImageView iv = iconView(wrapper);
        if (iv != null) {
            Drawable now = iv.getDrawable();
            if (left) {
                if (savedLeft == null) savedLeft = now;
            } else {
                if (savedRight == null) savedRight = now;
            }
        }
        Method set = Ref.methodOf(wrapper.getClass(), "setButtonIcon", int.class);
        Ref.call(wrapper, set, res);
        Logx.i(T, "bottom " + (left ? "左" : "右") + " 图标 → " + drawableName);
    }

    private static void restoreIcon(Activity a, String viewIdName, boolean left) {
        Drawable saved = left ? savedLeft : savedRight;
        if (saved == null) return;
        View wrapper = a.findViewById(id(a, viewIdName));
        if (wrapper == null) return;
        ImageView iv = iconView(wrapper);
        if (iv != null) iv.setImageDrawable(saved);
        Logx.i(T, "bottom " + (left ? "左" : "右") + " 图标已还原");
    }

    private static ImageView iconView(View wrapper) {
        Object v = Ref.callAny(wrapper, "getIconView");
        return v instanceof ImageView ? (ImageView) v : null;
    }

    // ── 分段按钮 ───────────────────────────────────────────────────────────

    /** 让胶囊的高亮落在指定分段上（2 = 课程表）；宿主回调会再进 changeType/s，由 MainTabHook 去抖 */
    static void selectSegment(Activity a, int idx) {
        if (MainTabHook.newBar) {
            NewBar.selectItem(a, idx == 2);
            return;
        }
        View picker = a.findViewById(id(a, "sliding_date_picker"));
        if (picker == null) {
            Logx.w(T, "sliding_date_picker 不存在");
            return;
        }
        Object seg = Ref.callAny(picker, "getSegmentPicker", "getF16532h");
        if (seg == null) {
            Logx.w(T, "segment picker 取不到");
            return;
        }
        Object v = Ref.call(seg, Ref.methodOf(seg.getClass(), "selectSegmentAt", int.class), idx);
        Logx.i(T, "segment → " + idx + (v == null ? "" : " ret=" + v));
    }

    private static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }
}





