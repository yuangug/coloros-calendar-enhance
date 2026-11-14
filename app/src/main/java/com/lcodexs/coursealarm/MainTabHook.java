package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 主界面改造：
 *   1. 底栏「日历 / 待办」分段按钮追加第三项「课程表」——点它直接进课表页
 *   2. 溢出菜单（右上角 ⋮）里原来的「课程表」项隐藏，入口只留在底栏
 *   3. 底栏右侧按钮换成 ⋮（把顶栏的「更多」挪到底栏），顶栏 overflow 隐藏
 *
 * 定位方式尽量不依赖混淆名：
 *   - 分段按钮走 COUISegmentButtonLayout.setSegmentButtons(String[])（COUI 公开 API，名字稳定）
 *   - 选中回调走 DateTabFoldLayout.changeType(int)（View 自定义方法，名稳定）
 *   - 菜单项走 COUIToolbar.inflateMenu(int) 之后按 id 隐藏
 */
final class MainTabHook {
    private static final String T = CourseAlarmEntry.TAG;

    /** R.id.school_table（宿主溢出菜单里的「课程表」） */
    private static final int ID_SCHOOL_TABLE = 0x7f0a070f;
    private static final int ID_SLIDING_PICKER = 0x7f0a0773;
    private static final int ID_TOOLBAR_OPTIONS = 0x7f0a088c;
    private static final int ID_FLOAT_ADD = 0x7f0a0385;

    private static final String TAB_COURSE = "课程表";
    private static final String TABLE_ACTIVITY =
            "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity";

    private MainTabHook() {
    }

    static void install(XposedModule module, ClassLoader host) {
        hookSegmentButtons(module, host);
        hookChangeType(module, host);
        hookToolbarMenu(module, host);
        hookBottomButtons(module, host);
        hookClickInterceptor(module, host);
    }

    // ── 1. 底栏分段按钮加「课程表」────────────────────────────────────────────

    private static void hookSegmentButtons(XposedModule module, ClassLoader host) {
        Class<?> seg = resolve(host, "com.coui.appcompat.segmentbutton.COUISegmentButtonLayout");
        if (seg == null) return;
        Method m = null;
        for (Method cand : seg.getMethods()) {
            if (cand.getName().equals("setSegmentButtons") && cand.getParameterCount() == 1
                    && cand.getParameterTypes()[0] == String[].class) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "setSegmentButtons(String[]) not found");
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("tab_add_course")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String[]) {
                        String[] in = (String[]) arg;
                        if (in.length == 2 && !TAB_COURSE.equals(in[1])) {
                            String[] out = new String[]{in[0], in[1], TAB_COURSE};
                            Logx.i(T, "bottom tab: " + in[0] + " / " + in[1] + " / " + TAB_COURSE);
                            return chain.proceed(new Object[]{out});
                        }
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked COUISegmentButtonLayout#setSegmentButtons");
    }

    // ── 2. 选中「课程表」→ 打开课表页，并复位到日历 ───────────────────────────

    private static void hookChangeType(XposedModule module, ClassLoader host) {
        Class<?> fold = resolve(host, "com.android.calendar.ui.main.calendar.view.DateTabFoldLayout");
        if (fold == null) return;
        Method m = null;
        for (Method cand : fold.getDeclaredMethods()) {
            if (cand.getName().equals("changeType") && cand.getParameterCount() == 1
                    && cand.getParameterTypes()[0] == int.class) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "DateTabFoldLayout#changeType(int) not found");
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("tab_course_click")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    Object self = chain.getThisObject();
                    if (a0 instanceof Integer && self instanceof View) {
                        int type = (Integer) a0;
                        Context ctx = ((View) self).getContext();
                        Activity act = asActivity(ctx);
                        if (type == 2) {
                            // 「课程表」= 主页里的第三个视图，不另开页面（底栏本来就在外面）
                            if (act != null) {
                                Logx.i(T, "bottom tab 课程表 → 主页内切换");
                                CourseTablePage.show(act);
                            } else {
                                Logx.w(T, "bottom tab 课程表: activity not found from " + ctx);
                            }
                            return null;   // 不交给宿主（它只有 日历/待办 两种类型）
                        }
                        if (act != null) CourseTablePage.hide(act);
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked DateTabFoldLayout#changeType");
    }

    /** Context 可能是 ContextWrapper 链，逐层剥到 Activity */
    private static Activity asActivity(Context c) {
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof android.content.ContextWrapper) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    private static volatile long lastOpenMs = 0L;

    /** 去重：分段按钮的回调会被连触发多次，1.5s 内只开一次课表页 */
    private static void openTable(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastOpenMs < 1500L) {
            Logx.i(T, "open table skipped (debounce " + (now - lastOpenMs) + "ms)");
            return;
        }
        lastOpenMs = now;
        try {
            Intent it = new Intent();
            it.setClassName(ctx.getPackageName(), TABLE_ACTIVITY);
            if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Logx.e(T, "open table failed", t);
        }
    }

    /** 点完把分段按钮恢复成「日历」，避免停在课程表那一格 */
    private static void resetSegment(View foldLayout) {
        try {
            Context ctx = foldLayout.getContext();
            if (!(ctx instanceof Activity)) return;
            View picker = ((Activity) ctx).findViewById(ID_SLIDING_PICKER);
            if (picker == null) return;
            Object seg = null;
            for (Method cand : picker.getClass().getMethods()) {
                if (cand.getParameterCount() == 0 && "getF16532h".equals(cand.getName())) {
                    seg = cand.invoke(picker);
                    break;
                }
            }
            if (seg == null) return;
            Method sel = seg.getClass().getMethod("selectSegmentAt", int.class);
            sel.invoke(seg, 0);
            Logx.i(T, "segment reset to 0");
        } catch (Throwable t) {
            Logx.w(T, "reset segment failed: " + t);
        }
    }

    /** Toolbar / PopupMenu / Menu 三种形态统一取 Menu */
    private static android.view.Menu menuOf(Object self) {
        if (self instanceof android.view.Menu) return (android.view.Menu) self;
        try {
            java.lang.reflect.Method gm = self.getClass().getMethod("getMenu");
            Object o = gm.invoke(self);
            if (o instanceof android.view.Menu) return (android.view.Menu) o;
        } catch (Throwable ignored) {
        }
        return null;
    }
    // ── 3. 溢出菜单里去掉「课程表」，并把底栏右侧换成 ⋮ ──────────────────────

    private static void hookToolbarMenu(XposedModule module, ClassLoader host) {
        Class<?> tb = resolve(host, "com.coui.appcompat.toolbar.COUIToolbar");
        if (tb == null) return;
        Method m = null;
        for (Method cand : tb.getMethods()) {
            if (cand.getName().equals("inflateMenu") && cand.getParameterCount() == 1
                    && cand.getParameterTypes()[0] == int.class) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "COUIToolbar#inflateMenu(int) not found");
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("hide_menu_school_table")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    android.view.Menu menu = menuOf(self);
                    if (menu != null) {
                        android.view.MenuItem item = menu.findItem(ID_SCHOOL_TABLE);
                        if (item != null && item.isVisible()) {
                            item.setVisible(false);
                            Logx.i(T, "overflow menu: hid 课程表 item");
                        }
                        // 导入 / 导出 / 课表主题 也加到主页菜单里（课表已搬进主页）
                        if (self instanceof View) {
                            Activity act = asActivity(((View) self).getContext());
                            if (act != null) {
                                try {
                                    Hooks.injectMenuItems(act, menu);
                                    Logx.i(T, "overflow menu: injected items, size=" + menu.size());
                                } catch (Throwable t) {
                                    Logx.w(T, "inject menu failed: " + t);
                                }
                            }
                        }
                    } else {
                        Logx.w(T, "overflow menu: menu not resolved from " + self);
                    }
                    return r;
                });
        Logx.i(T, "hooked COUIToolbar#inflateMenu");
    }

    /**
     * 底栏右侧按钮（新建日程位）换成「更多」：
     * 点击弹宿主的溢出菜单，这样右上角那个 ⋮ 就能收到底栏。
     */
    private static void hookBottomButtons(XposedModule module, ClassLoader host) {
        Class<?> fold = resolve(host, "com.android.calendar.ui.main.calendar.view.DateTabFoldLayout");
        if (fold == null) return;
        Method m = null;
        for (Method cand : fold.getDeclaredMethods()) {
            if (cand.getName().equals("onFinishInflate") && cand.getParameterCount() == 0) {
                m = cand;
                break;
            }
        }
        if (m == null) return;
        m.setAccessible(true);
        module.hook(m)
                .setId("bottom_more_button")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        // 布局刚 inflate 完，等宿主自己设好 listener 再动手
                        ((View) self).post(() -> rewireBottomBar((View) self));
                    }
                    return r;
                });
        Logx.i(T, "hooked DateTabFoldLayout#onFinishInflate (bottom bar)");
    }

    private static void rewireBottomBar(View foldLayout) {
        try {
            Context ctx = foldLayout.getContext();
            if (!(ctx instanceof Activity)) return;
            Activity act = (Activity) ctx;

            View toolbar = act.findViewById(ID_TOOLBAR_OPTIONS);
            final View add = act.findViewById(ID_FLOAT_ADD);
            if (add == null) {
                Logx.w(T, "float_add_button not found, skip bottom bar rewire");
                return;
            }
            // 先把顶部 overflow 收起来（避免和底栏重复）
            if (toolbar != null) hideToolbarOverflow(toolbar);
            if (toolbar == null) return;

            swapIconToMore(add, ctx);
            add.setOnClickListener(v -> {
                try {
                    Method show = toolbar.getClass().getMethod("showOverflowMenu");
                    Object ok = show.invoke(toolbar);
                    Logx.i(T, "bottom ⋮ clicked → overflow=" + ok);
                } catch (Throwable t) {
                    Logx.w(T, "showOverflowMenu failed: " + t);
                }
            });
            add.setOnLongClickListener(v -> {
                Logx.i(T, "bottom + long-press → keep host behaviour");
                return false;
            });
            Logx.i(T, "bottom right button rewired to overflow menu");
        } catch (Throwable t) {
            Logx.e(T, "rewire bottom bar failed", t);
        }
    }

    private static final ThreadLocal<Boolean> IN_SET_CLICK = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /**
     * 底栏右侧按钮的点击被宿主自己设了 listener（OnTouchListener 层级更早），
     * 单纯 setOnClickListener 会被覆盖。这里 hook View.setOnClickListener：
     * 当目标是我们那个按钮时，直接换成本模块的 listener（弹溢出菜单），丢弃宿主的。
     */
    private static void hookClickInterceptor(XposedModule module, ClassLoader host) {
        try {
            Method m = android.view.View.class.getMethod("setOnClickListener",
                    android.view.View.OnClickListener.class);
            module.hook(m)
                    .setId("bottom_add_click")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof View && !IN_SET_CLICK.get()) {
                            View v = (View) self;
                            if (v.getId() == ID_FLOAT_ADD) {
                                IN_SET_CLICK.set(Boolean.TRUE);
                                try {
                                    v.setOnClickListener(x -> openOverflow(v));
                                    v.setOnLongClickListener(x -> {
                                        newEvent(v.getContext());
                                        return true;
                                    });
                                } finally {
                                    IN_SET_CLICK.set(Boolean.FALSE);
                                }
                                Logx.i(T, "bottom right click taken over (overflow / long-press = new event)");
                                return null;   // 不要再装宿主的 listener
                            }
                        }
                        return chain.proceed();
                    });
            Logx.i(T, "hooked View#setOnClickListener (bottom add button)");
        } catch (Throwable t) {
            Logx.w(T, "hookClickInterceptor failed: " + t);
        }
    }

    private static void openOverflow(View from) {
        try {
            Context ctx = from.getContext();
            if (!(ctx instanceof Activity)) return;
            View toolbar = ((Activity) ctx).findViewById(ID_TOOLBAR_OPTIONS);
            if (toolbar == null) {
                Logx.w(T, "openOverflow: toolbar not found");
                return;
            }
            // 顶栏的 overflow 按钮已被我们隐藏，showOverflowMenu() 会返回 false，
            // 所以直接把宿主的菜单项读出来，用 PopupMenu 自己弹一份。
            android.view.Menu rawMenu = null;
            try {
                rawMenu = (android.view.Menu) toolbar.getClass().getMethod("getMenu").invoke(toolbar);
            } catch (Throwable ignored) {
            }
            final android.view.Menu menu = rawMenu;
            if (menu == null || menu.size() == 0) {
                Method show = toolbar.getClass().getMethod("showOverflowMenu");
                Logx.i(T, "bottom ⋮ → fallback overflow=" + show.invoke(toolbar));
                return;
            }
            android.widget.PopupMenu pm = new android.widget.PopupMenu(ctx, from);
            android.view.Menu target = pm.getMenu();
            for (int i = 0; i < menu.size(); i++) {
                android.view.MenuItem mi = menu.getItem(i);
                if (!mi.isVisible()) continue;
                target.add(0, mi.getItemId(), i, mi.getTitle());
            }
            pm.setOnMenuItemClickListener(item -> {
                try {
                    menu.performIdentifierAction(item.getItemId(), 0);
                    Logx.i(T, "bottom menu → " + item.getTitle());
                } catch (Throwable t) {
                    Logx.w(T, "performIdentifierAction failed: " + t);
                }
                return true;
            });
            pm.show();
            Logx.i(T, "bottom ⋮ → PopupMenu(" + target.size() + " items)");
        } catch (Throwable t) {
            Logx.w(T, "openOverflow failed: " + t);
        }
    }

    /** 长按找回「新建日程」：走系统日历的插入 Intent，不依赖宿主内部方法 */
    private static void newEvent(Context ctx) {
        try {
            Intent it = new Intent(Intent.ACTION_INSERT)
                    .setData(android.provider.CalendarContract.Events.CONTENT_URI);
            if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            Logx.i(T, "long press → ACTION_INSERT event");
        } catch (Throwable t) {
            Logx.w(T, "newEvent failed: " + t);
        }
    }
    /** 把底栏右侧按钮的图标换成宿主自己的「更多」图标 */
    private static void swapIconToMore(View root, Context ctx) {
        try {
            android.widget.ImageView iv = findFirstImageView(root);
            if (iv == null) {
                Logx.w(T, "no ImageView inside float_add_button");
                return;
            }
            int rid = ctx.getResources().getIdentifier("table_action_more", "drawable", ctx.getPackageName());
            if (rid == 0) {
                Logx.w(T, "drawable table_action_more not found");
                return;
            }
            iv.setImageResource(rid);
            Logx.i(T, "bottom right icon → table_action_more");
        } catch (Throwable t) {
            Logx.w(T, "swapIconToMore failed: " + t);
        }
    }

    private static android.widget.ImageView findFirstImageView(View v) {
        if (v instanceof android.widget.ImageView) return (android.widget.ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ImageView found = findFirstImageView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    /** 把 Toolbar 内部的 ActionMenuView（overflow 按钮所在）隐藏 */
    private static void hideToolbarOverflow(View toolbar) {
        try {
            if (!(toolbar instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) toolbar;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String cn = c.getClass().getName();
                if (!cn.contains("ActionMenuView")) continue;
                if (c instanceof ViewGroup) {
                    ViewGroup am = (ViewGroup) c;
                    // 用 INVISIBLE 而不是 GONE：GONE 会让 showOverflowMenu() 返回 false（宿主认为没有可弹的项）
                    for (int j = 0; j < am.getChildCount(); j++) am.getChildAt(j).setVisibility(View.INVISIBLE);
                } else {
                    c.setVisibility(View.INVISIBLE);
                }
                Logx.i(T, "toolbar overflow hidden (" + cn + ")");
            }
        } catch (Throwable t) {
            Logx.w(T, "hideToolbarOverflow failed: " + t);
        }
    }

    private static Class<?> resolve(ClassLoader host, String name) {
        try {
            return Class.forName(name, false, host);
        } catch (Throwable t) {
            Logx.w(T, "class not found: " + name + " (" + t + ")");
            return null;
        }
    }
}
