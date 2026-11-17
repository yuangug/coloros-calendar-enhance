package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 日历 17.x（ColorOS 17 / Android 17）的新底栏：{@code COUIBottomFloatingAccessoryNavigationBar}。
 *
 * 16.24.2 的底栏是「纯文字分段按钮」COUISegmentButtonLayout + 两个圆钮（float_view_menu_button /
 * float_add_button）；17.3.70 换成了 COUI 的「图标 + 文字」浮动胶囊，左右各挂一个圆形附件按钮，
 * 原来那套 id 全部消失（见 analysis/findings-bar-17.3.70.md）：
 *
 *   宿主搭底栏：MainCalendarFragment#h7(int)
 *     slidingDatePicker.addItems(list(日历 item, 待办 item))       ← 我们在这里补第三格
 *     setDispatchItemClickWhenAlreadySelected(false)
 *     setOnItemSelectedListener(f2)                              ← 点选 → foldLayout.s(index)
 *     setStartAccessoryIcon(ic_for_todo_24) / setEndAccessoryIcon(ic_add_event)
 *   宿主切页：DateTabFoldLayout#s(int) → X(from,to)（16.x 叫 changeType(int)）
 *   宿主同步胶囊：slidingDatePicker.setSelectedItemId(nav_sliding_calendar|nav_sliding_second)
 *
 * 所以本类只做四件事，全部走反射，不 import 宿主类：
 *   1. addItems(List) 追加「课程表」item（id = {@link #ITEM_ID}，图标 = 宿主 course_table_icon 染色）
 *   2. 把左右两个附件按钮的点击 listener 包一层：课表页里改成 周次选择 / 课表 ⋮
 *   3. 换附件图标（周次选择用 ic_for_week_24，⋮ 用 table_action_more），离开时还原
 *   4. 把胶囊高亮钉在「课程表」上（setSelectedItemId(ITEM_ID)）
 */
final class NewBar {
    private static final String T = CourseAlarmEntry.TAG;

    static final String CLS_BAR =
            "com.coui.appcompat.bottomfloatingaccessorynavigationbar.COUIBottomFloatingAccessoryNavigationBar";
    static final String CLS_ITEM =
            "com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBarItem";
    static final String CLS_ITEM_BUILDER = CLS_ITEM + "$Builder";
    static final String CLS_MENU_VIEW =
            "com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBarMenuView";
    static final String CLS_ACCESSORY_LISTENER = CLS_BAR + "$OnAccessoryClickListener";

    /** 第三格（课程表）的 item id：宿主 id 都在 0x7f0a****，这里不会撞 */
    static final int ITEM_ID = 0x7E0A0001;
    static final String TAB_TITLE = "课程表";

    private static final String ID_BAR = "sliding_date_picker";
    private static final String ID_NAV_CALENDAR = "nav_sliding_calendar";
    private static final String ID_NAV_SECOND = "nav_sliding_second";

    private NewBar() {
    }

    /** 新底栏在不在：17.x 才有这个类，16.x 取不到 */
    static boolean available(ClassLoader host) {
        return Ref.cls(host, CLS_BAR) != null
                && Ref.cls(host, CLS_ITEM_BUILDER) != null
                && Ref.cls(host, CLS_MENU_VIEW) != null;
    }

    static void install(XposedModule module, ClassLoader host) {
        hookAddItems(module, host);
        hookAccessoryClicks(module, host);
        hookBarTouch(module, host);
    }

    // ── 1. 给胶囊补第三格 ────────────────────────────────────────────────────

    private static void hookAddItems(XposedModule module, ClassLoader host) {
        Class<?> bar = Ref.cls(host, CLS_BAR);
        if (bar == null) return;
        Method m = Ref.methodOf(bar, "addItems", List.class);
        if (m == null) {
            Logx.w(T, "COUIBottomFloatingAccessoryNavigationBar#addItems(List) 不存在");
            return;
        }
        module.hook(m)
                .setId("bar_add_items")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object self = chain.getThisObject();
                    Object a0 = chain.getArg(0);
                    if (!(self instanceof View) || !(a0 instanceof List)) return chain.proceed();
                    if (!isMainBar((View) self)) return chain.proceed();

                    Object item = null;
                    try {
                        item = buildItem((View) self);
                    } catch (Throwable t) {
                        Logx.e(T, "new bar: 构建课程表 item 失败", t);
                    }
                    if (item == null) return chain.proceed();

                    List<?> in = (List<?>) a0;
                    List<Object> out = new ArrayList<>(in.size() + 1);
                    for (Object o : in) {
                        if (o != null && itemId(o) == ITEM_ID) continue;   // 幂等：已经补过就不重复
                        out.add(o);
                    }
                    out.add(item);
                    Logx.i(T, "new bar: 底栏 " + in.size() + " 格 → " + out.size()
                            + " 格（第 " + out.size() + " 格 = 课程表）");
                    // 启动那一瞬宿主会刷几千行日志，logd 会把我们这行挤掉；cache/lspx_bar.log 留一份硬证据
                    noteEvidence((View) self, "addItems in=" + in.size() + " out=" + out.size());
                    return chain.proceed(new Object[]{out});
                });
        Logx.i(T, "hooked COUIBottomFloatingAccessoryNavigationBar#addItems (补「课程表」格)");
    }

    /** 只认主页底栏（id = sliding_date_picker），别的页面用同一个控件时不动 */
    private static boolean isMainBar(View v) {
        int want = id(v.getContext(), ID_BAR);
        return want == 0 || v.getId() == want;
    }

    private static Object buildItem(View bar) throws Exception {
        Context c = bar.getContext();
        ClassLoader l = bar.getClass().getClassLoader();
        Class<?> bcls = Class.forName(CLS_ITEM_BUILDER, false, l);
        Object b = bcls.getDeclaredConstructor().newInstance();
        Ref.callName(b, "setId", ITEM_ID);
        Ref.callName(b, "setTitle", TAB_TITLE);
        Drawable icon = tabIcon(c);
        if (icon != null) {
            Ref.callName(b, "setIcon", icon);
        } else {
            Logx.w(T, "new bar: 拿不到 course_table_icon，课程表格子无图标");
        }
        return Ref.callName(b, "build");
    }

    private static int itemId(Object item) {
        Object v = Ref.callAny(item, "getId");
        return v instanceof Integer ? (Integer) v : 0;
    }

    /** 旁路证据：写宿主 cache（启动瞬间 logd 会把我们的 I 行挤掉，文件不会） */
    private static void noteEvidence(View v, String msg) {
        try {
            java.io.File f = new java.io.File(v.getContext().getCacheDir(), "lspx_bar.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + "  " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 课程表 tab 的图标：宿主自己的 course_table_icon（24dp 矢量），按宿主菜单图标色
     * （menu_ic_tint_color_black，深色模式会自动换成浅色）染色，选中 90% / 未选中 72% 不透明度
     * —— 和 nav 那两格（fillAlpha 0.9 / 0.72）同一套视觉。
     */
    private static Drawable tabIcon(Context c) {
        try {
            android.content.res.Resources r = c.getResources();
            String pkg = c.getPackageName();
            int tint = iconTint(c, r, pkg);
            int selected = (tint & 0x00FFFFFF) | 0xE6000000;
            int normal = (tint & 0x00FFFFFF) | 0xB8000000;
            int px = Math.round(24f * r.getDisplayMetrics().density);

            // 同一形状两态：未选中描边（中空）+ 选中实心，和宿主 nav 两格的 _normal/_selected 一致
            Drawable filled = TabGlyph.make(true, selected, px);
            Drawable hollow = TabGlyph.make(false, normal, px);
            if (filled != null && hollow != null) {
                android.graphics.drawable.StateListDrawable sl =
                        new android.graphics.drawable.StateListDrawable();
                sl.addState(new int[]{android.R.attr.state_selected}, filled);
                sl.addState(new int[]{android.R.attr.state_checked}, filled);
                sl.addState(new int[0], hollow);
                sl.setEnterFadeDuration(200);
                sl.setExitFadeDuration(100);
                return sl;
            }

            // 兜底：字形解析失败就用宿主那个实心 vector（老行为）
            int res = r.getIdentifier("course_table_icon", "drawable", pkg);
            if (res == 0) return null;
            Drawable base = r.getDrawable(res, c.getTheme());
            if (base == null) return null;
            Drawable d = base.mutate();
            d.setTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_selected}, new int[0]},
                    new int[]{selected, normal}));
            return d;
        } catch (Throwable t) {
            Logx.w(T, "new bar: tabIcon 失败 " + t);
            return null;
        }
    }

    /**
     * 图标颜色跟随主题：优先取 COUI 的 couiColorLabelPrimary（深色模式是白、浅色是黑），
     * 取不到再退回 menu_ic_tint_color_black。宿主 nav 两格在 drawable-night 里有白色版本，
     * 我们只有一个 course_table_icon，所以颜色必须自己按主题算，否则深色下会看不见。
     */
    private static int iconTint(Context c, android.content.res.Resources r, String pkg) {
        int attrId = r.getIdentifier("couiColorLabelPrimary", "attr", pkg);
        if (attrId != 0) {
            try {
                android.util.TypedValue tv = new android.util.TypedValue();
                if (c.getTheme().resolveAttribute(attrId, tv, true)) {
                    if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                            && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data;
                    }
                    if (tv.resourceId != 0) return r.getColor(tv.resourceId, c.getTheme());
                }
            } catch (Throwable ignored) {
            }
        }
        int colorId = r.getIdentifier("menu_ic_tint_color_black", "color", pkg);
        if (colorId != 0) {
            try {
                return r.getColor(colorId, c.getTheme());
            } catch (Throwable ignored) {
            }
        }
        return 0xFF000000;
    }

    // ── 2. 左右附件按钮：课表页里换成课表自己的两个动作 ────────────────────────

    private static void hookAccessoryClicks(XposedModule module, ClassLoader host) {
        Class<?> bar = Ref.cls(host, CLS_BAR);
        Class<?> iface = Ref.cls(host, CLS_ACCESSORY_LISTENER);
        if (bar == null || iface == null) {
            Logx.w(T, "new bar: OnAccessoryClickListener 取不到，附件按钮点击不接管");
            return;
        }
        hookAccessorySetter(module, bar, iface, "setOnStartAccessoryClickListener", true);
        hookAccessorySetter(module, bar, iface, "setOnEndAccessoryClickListener", false);
    }

    private static void hookAccessorySetter(XposedModule module, Class<?> bar, Class<?> iface,
                                            final String name, final boolean start) {
        Method m = Ref.methodOf(bar, name, iface);
        if (m == null) {
            Logx.w(T, "new bar: " + name + " 不存在");
            return;
        }
        module.hook(m)
                .setId("bar_accessory_" + (start ? "start" : "end"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    final Object original = chain.getArg(0);
                    if (original == null || !iface.isInstance(original)) return chain.proceed();
                    Object proxy = Proxy.newProxyInstance(
                            original.getClass().getClassLoader(), new Class<?>[]{iface},
                            (p, method, args) -> {
                                if ("onAccessoryClick".equals(method.getName())
                                        && BarMode.active && HostTablePage.showing()) {
                                    Object anchor = (args != null && args.length > 0) ? args[0] : null;
                                    if (anchor instanceof View) {
                                        View v = (View) anchor;
                                        if (start) {
                                            Logx.i(T, "new bar: 左附件 → 课表周次选择");
                                            HostTablePage.showWeekPicker(v);
                                        } else {
                                            Logx.i(T, "new bar: 右附件 → 课表 ⋮");
                                            HostTablePage.showMenu(v);
                                        }
                                        return null;
                                    }
                                }
                                try {
                                    return method.invoke(original, args);
                                } catch (InvocationTargetException ite) {
                                    throw ite.getCause();
                                }
                            });
                    return chain.proceed(new Object[]{proxy});
                });
        Logx.i(T, "hooked " + name + " (课表页接管)");
    }

    // ── 3. 记「用户真的点了底栏」（保护窗靠它区分用户点击和宿主同步） ───────────

    private static void hookBarTouch(XposedModule module, ClassLoader host) {
        Class<?> mv = Ref.cls(host, CLS_MENU_VIEW);
        if (mv == null) return;
        Method m = Ref.methodOf(mv, "dispatchTouchEvent", MotionEvent.class);
        if (m == null) return;
        module.hook(m)
                .setId("bar_touch_new")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    if (a0 instanceof MotionEvent
                            && ((MotionEvent) a0).getActionMasked() == MotionEvent.ACTION_DOWN) {
                        MainTabHook.noteUserBarTouch();
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked COUIBottomFloatingNavigationBarMenuView#dispatchTouchEvent (用户点击跟踪)");
    }

    // ── 4. 胶囊高亮 / 附件图标 ───────────────────────────────────────────────

    static Object barOf(Activity a) {
        if (a == null) return null;
        int id = id(a, ID_BAR);
        if (id == 0) return null;
        View v = a.findViewById(id);
        return v == null ? null : v;
    }

    static int calendarItemId(Activity a) {
        return a == null ? 0 : id(a, ID_NAV_CALENDAR);
    }

    static int secondItemId(Activity a) {
        return a == null ? 0 : id(a, ID_NAV_SECOND);
    }

    /** 把胶囊高亮钉到「课程表」/「日历」 */
    static void selectItem(Activity a, boolean table) {
        Object bar = barOf(a);
        if (bar == null) {
            Logx.w(T, "new bar: 底栏控件不存在，无法切换高亮");
            return;
        }
        int want = table ? ITEM_ID : calendarItemId(a);
        if (want == 0) return;
        Object v = Ref.callName(bar, "setSelectedItemId", want);
        Logx.i(T, "new bar: 胶囊高亮 → " + (table ? "课程表" : "日历")
                + " (0x" + Integer.toHexString(want) + ")" + (v == null ? "" : " ret=" + v));
    }

    /** 课表页：左右附件换成 周次选择 / ⋮；离开时还原（BarMode 持有 saved 引用） */
    static Drawable swapAccessory(Object bar, boolean start, Activity a, String drawableName) {
        if (bar == null) return null;
        Drawable saved = accessoryIcon(bar, start);
        int res = drawableName == null ? 0
                : a.getResources().getIdentifier(drawableName, "drawable", a.getPackageName());
        if (res == 0) {
            Logx.w(T, "new bar: drawable 不存在 " + drawableName);
            return saved;
        }
        Drawable d;
        try {
            d = a.getResources().getDrawable(res, a.getTheme());
        } catch (Throwable t) {
            return saved;
        }
        Ref.callName(bar, start ? "setStartAccessoryDrawable" : "setEndAccessoryDrawable", d);
        Logx.i(T, "new bar: " + (start ? "左" : "右") + "附件图标 → " + drawableName);
        return saved;
    }

    static void restoreAccessory(Object bar, boolean start, Drawable saved) {
        if (bar == null || saved == null) return;
        Ref.callName(bar, start ? "setStartAccessoryDrawable" : "setEndAccessoryDrawable", saved);
        Logx.i(T, "new bar: " + (start ? "左" : "右") + "附件图标已还原");
    }

    static Drawable accessoryIcon(Object bar, boolean start) {
        Object view = Ref.call(bar, Ref.methodOf(bar.getClass(),
                start ? "getStartAccessoryView" : "getEndAccessoryView"));
        Object d = view == null ? null : Ref.callAny(view, "getIconDrawable");
        return d instanceof Drawable ? (Drawable) d : null;
    }

    private static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }
}
