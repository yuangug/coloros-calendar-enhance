package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 主界面改造（第二版：课程表真正成为主页的一个页面）。
 *
 *   底栏胶囊：日历 | 待办 | 课程表 —— 第三段由 COUISegmentButtonLayout#setSegmentButtons 注入
 *   切到课程表：不打开任何 Activity，直接把宿主自己的课表视图叠进 fragment_container
 *               （{@link HostTablePage}，ViewPager2 + 宿主 CourseWeekViewPagerAdapter + TableView）
 *   底栏左右钮：日历/待办页保持宿主原样；课程表页换成课表自己的两个按钮
 *               （{@link BarMode}：左＝周次选择，右＝课表 ⋮）
 *   顶栏：课表页只留标题「课程表」，月/周切换胶囊与副标题先收起来，离开时原样还回去
 *   溢出菜单：宿主的「课程表」项隐藏（入口已在底栏），另外注入 导入 / 导出 / 课表主题
 *
 * 定位方式尽量不依赖混淆名：
 *   - 分段按钮走 COUISegmentButtonLayout.setSegmentButtons(String[])
 *   - 选中回调走 DateTabFoldLayout.changeType(int)
 *   - 底栏圆钮按宿主自己的 id（float_view_menu_button / float_add_button）
 *   - 课表视图的适配器类从 SchoolTimeTableActivity 的字段类型反查（它的类名被 R8 混淆成 b）
 */
final class MainTabHook {
    private static final String T = CourseAlarmEntry.TAG;

    /** R.id.school_table（宿主溢出菜单里的「课程表」） */
    private static final int ID_SCHOOL_TABLE = 0x7f0a070f;

    private static final String TAB_COURSE = "课程表";
    private static final int TAB_INDEX_COURSE = 2;

    /** 底栏版本：false = 16.x 纯文字分段按钮；true = 17.x COUI 图标胶囊（见 {@link NewBar}） */
    static volatile boolean newBar = false;

    private MainTabHook() {
    }

    static void install(XposedModule module, ClassLoader host) {
        newBar = NewBar.available(host);
        Logx.i(T, "底栏版本：" + (newBar
                ? "17.x COUIBottomFloatingAccessoryNavigationBar（图标胶囊 + 左右附件）"
                : "16.x COUISegmentButtonLayout（文字分段 + 两个圆钮）"));
        if (newBar) {
            NewBar.install(module, host);
        } else {
            hookSegmentButtons(module, host);
            hookBottomClicks(module, host);
            hookBarTouch(module, host);
        }
        hookChangeType(module, host);
        hookToolbarMenu(module, host);
        hookHomePopupFilter(module, host);
        hookMainResume(module, host);
        hookMainActivityResult(module, host);
        hookMainLifecycle(module, host);
        TableViewBridge.install(module, host);   // 点课程 → 宿主详情面板（两版都有）

    }

    // ── 1. 底栏分段按钮加「课程表」────────────────────────────────────────────

    private static void hookSegmentButtons(XposedModule module, ClassLoader host) {
        Class<?> seg = Ref.cls(host, "com.coui.appcompat.segmentbutton.COUISegmentButtonLayout");
        if (seg == null) return;
        Method m = Ref.methodOf(seg, "setSegmentButtons", String[].class);
        if (m == null) {
            Logx.w(T, "setSegmentButtons(String[]) not found");
            return;
        }
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

    // ── 2. 选中「课程表」→ 主页内嵌课表页（不打开 Activity） ─────────────────────

    private static long lastSwitchMs = 0L;

    static void openCoursePage(Activity activity) {
        applyTitle(activity, true);
        HostTablePage.show(activity);
        BarMode.enter(activity);
        BarMode.selectSegment(activity, TAB_INDEX_COURSE);
    }

    static boolean enforceCourseTitle(Activity activity) {
        boolean changed = false;
        for (String name : TITLE_VIEWS) {
            View view = activity.findViewById(id(activity, name));
            if (view != null && view.getVisibility() != View.GONE) {
                if (!titleVisibility.containsKey(view)) titleVisibility.put(view, view.getVisibility());
                view.setVisibility(View.GONE);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 「课表管理里切换课表」的保护窗：拦截掉老课表页之后，宿主 onResume 会把分段按钮
     * 同步回「日历」并回调 changeType(0)，把我们刚重载的课表页又收起来。
     * 这 2.5 秒内：忽略 0/1 的切页回调，并把分段重新钉在「课程表」。
     */
    private static volatile long keepTableUntil = 0L;

    /** 上一次真正按到底栏的时刻：用来区分「用户点的」和「宿主自己同步状态触发的」 */
    private static volatile long lastBarTouchMs = 0L;

    /** 新底栏（COUIBottomFloatingNavigationBarMenuView）在 ACTION_DOWN 时回填 */
    static void noteUserBarTouch() {
        lastBarTouchMs = System.currentTimeMillis();
    }

    static void keepTableMode() {
        keepTableUntil = System.currentTimeMillis() + 1500L;
        Logx.i(T, "keepTableMode 开启（切换课表后回到课表页）");
    }

    private static boolean inKeepWindow() {
        return System.currentTimeMillis() < keepTableUntil;
    }

    /** 保护窗里但确实是用户点的 → 让路，别拦人家切页 */
    private static boolean isUserTap() {
        return System.currentTimeMillis() - lastBarTouchMs < 900L;
    }

    private static void hookChangeType(XposedModule module, ClassLoader host) {
        Method m;
        try { m = HostCompat.changeType(host); }
        catch (ReflectiveOperationException error) {
            Logx.e(T, "底栏切页接口不可用", error);
            return;
        }
        final String hooked = m.getName();
        module.hook(m)
                .setId("tab_switch")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    Object self = chain.getThisObject();
                    if (!(a0 instanceof Integer) || !(self instanceof View)) return chain.proceed();
                    int type = (Integer) a0;
                    Activity act = asActivity(((View) self).getContext());
                    if (act == null) return chain.proceed();

                    if (type == TAB_INDEX_COURSE) {
                        long now = System.currentTimeMillis();
                        if (!HostTablePage.showing()) {
                            Logx.i(T, "tab(" + hooked + ") → 课程表（主页内嵌，不新建页面）");
                            openCoursePage(act);
                        } else if (now - lastSwitchMs > 400) {
                            Logx.i(T, "tab(" + hooked + ") → 课程表（已在课表页，忽略重复回调）");
                            BarMode.selectSegment(act, TAB_INDEX_COURSE);
                        }
                        lastSwitchMs = now;
                        return null;   // 不交给宿主：它只有 日历 / 待办 两种类型
                    }

                    if (inKeepWindow() && !isUserTap()) {
                        Logx.i(T, "tab(" + hooked + ") → " + (type == 1 ? "待办" : "日历")
                                + " 被忽略（切换课表后的保护窗，非用户点击）");
                        BarMode.selectSegment(act, TAB_INDEX_COURSE);
                        return null;
                    }
                    keepTableUntil = 0L;   // 用户真的切了，保护窗立即失效
                    if (HostTablePage.showing()) {
                        Logx.i(T, "tab(" + hooked + ") → " + (type == 1 ? "待办" : "日历")
                                + "（收起课表页）");
                        HostTablePage.hide();
                        BarMode.exit(act);
                        applyTitle(act, false);
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked DateTabFoldLayout#" + hooked + "(int)");
    }

    /** Context 可能是 ContextWrapper 链，逐层剥到 Activity */
    private static Activity asActivity(android.content.Context c) {
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

    // ── 3. 溢出菜单：藏掉宿主「课程表」，注入 导入 / 导出 / 课表主题 ─────────────

    private static void hookToolbarMenu(XposedModule module, ClassLoader host) {
        Class<?> tb = Ref.cls(host, "com.coui.appcompat.toolbar.COUIToolbar");
        if (tb == null) return;
        Method m = Ref.methodOf(tb, "inflateMenu", int.class);
        if (m == null) {
            Logx.w(T, "COUIToolbar#inflateMenu(int) not found");
            return;
        }
        module.hook(m)
                .setId("main_toolbar_menu")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    android.view.Menu menu = menuOf(self);
                    if (menu != null && self instanceof View) {
                        // 把「课程表」从菜单里**删掉**，不能只 setVisible(false)：
                        // COUIActionMenuView#tryBuildOverflowMenu 按 mMenu.getNonActionItems() 建弹出列表，
                        // 点击回调又用同一个列表按 **位置** 取 item —— 隐藏项仍留在列表里，
                        // 于是「显示少一项、派发多一项」：点「跳转指定日期」实际执行的是「课程表」。
                        // （宿主对 findItem(school_table) 做了 null 判断，删掉是安全的。）
                        java.util.List<Integer> kill = new java.util.ArrayList<>();
                        int sid = ((View) self).getContext().getResources()
                                .getIdentifier("school_table", "id",
                                        ((View) self).getContext().getPackageName());
                        if (sid != 0 && menu.findItem(sid) != null) kill.add(sid);
                        for (int i = 0; i < menu.size(); i++) {
                            android.view.MenuItem mi = menu.getItem(i);
                            if (mi == null) continue;
                            CharSequence title = mi.getTitle();
                            if (title != null && TAB_COURSE.contentEquals(title)
                                    && !kill.contains(mi.getItemId())) {
                                kill.add(mi.getItemId());
                            }
                        }
                        for (int id : kill) {
                            menu.removeItem(id);
                            Logx.i(T, "overflow menu: removed 课程表 item (id=0x"
                                    + Integer.toHexString(id) + ")");
                        }
                        // 不再往主页顶栏菜单里塞「导入/导出/课表主题」：这些入口已经全部搬到
                        // 课表页右下角的 ⋮（COUIPopupListWindow）。日历/待办页的 ⋮ 保持宿主原样。
                    }
                    return r;
                });
        Logx.i(T, "hooked COUIToolbar#inflateMenu");
    }

    /**
     * 主页右上角 ⋮ 的弹出菜单是宿主自己用 COUIPopupListWindow 现搭的（并不走我们 hook 的
     * COUIToolbar#inflateMenu 那个 Menu 实例），所以「藏掉课程表项」要把它的条目列表也过滤一遍：
     * 标题等于「课程表」的条目直接丢掉（入口已经在底栏第三段）。
     */
    private static void hookHomePopupFilter(XposedModule module, ClassLoader host) {
        Class<?> win = Ref.cls(host, "com.coui.appcompat.poplist.COUIPopupListWindow");
        Class<?> item = Ref.cls(host, "com.coui.appcompat.poplist.PopupListItem");
        if (win == null || item == null) return;
        Method m = Ref.methodOf(win, "setItemList", java.util.List.class);
        if (m == null) {
            Logx.w(T, "COUIPopupListWindow#setItemList(List) not found");
            return;
        }
        Method getTitle = Ref.methodByArity(item, "getTitle", 0);
        module.hook(m)
                .setId("home_popup_filter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    if (!(a0 instanceof java.util.List) || getTitle == null) return chain.proceed();
                    java.util.List<?> in = (java.util.List<?>) a0;
                    boolean hit = false;
                    for (Object it : in) {
                        Object t = Ref.call(it, getTitle);
                        if (t instanceof CharSequence && TAB_COURSE.contentEquals((CharSequence) t)) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) return chain.proceed();
                    java.util.List<Object> out = new java.util.ArrayList<>();
                    for (Object it : in) {
                        Object t = Ref.call(it, getTitle);
                        if (t instanceof CharSequence && TAB_COURSE.contentEquals((CharSequence) t)) continue;
                        out.add(it);
                    }
                    Logx.i(T, "home popup: 过滤掉「课程表」项（剩 " + out.size() + " 项）");
                    return chain.proceed(new Object[]{out});
                });
        Logx.i(T, "hooked COUIPopupListWindow#setItemList (filter 课程表)");
    }

    private static android.view.Menu menuOf(Object self) {
        if (self instanceof android.view.Menu) return (android.view.Menu) self;
        try {
            Method gm = self.getClass().getMethod("getMenu");
            Object o = gm.invoke(self);
            if (o instanceof android.view.Menu) return (android.view.Menu) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── 4. 底栏左右圆钮的点击归属（只在课表页接管） ────────────────────────────

    /**
     * 用 performClick 拦截，而不是替换宿主的 OnClickListener：
     *   - 日历/待办页：BarMode.active=false，直接 proceed，宿主行为一点不改
     *   - 课表页：两个圆钮的语义换成课表自己的，返回 false 让宿主 listener 不执行
     * 热路径先看一个 volatile 布尔，开销可以忽略。
     */
    private static void hookBottomClicks(XposedModule module, ClassLoader host) {
        try {
            Method m = android.view.View.class.getMethod("performClick");
            module.hook(m)
                    .setId("bottom_bar_click")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (BarMode.active) {
                            Object self = chain.getThisObject();
                            if (self instanceof View && BarMode.handleClick((View) self)) {
                                return false;
                            }
                        }
                        return chain.proceed();
                    });
            Logx.i(T, "hooked View#performClick (bottom bar per-page)");
        } catch (Throwable t) {
            Logx.w(T, "hook performClick failed: " + t);
        }
    }

    // ── 4b. 记一次「用户真的按了底栏」────────────────────────────────────────

    private static void hookBarTouch(XposedModule module, ClassLoader host) {
        Class<?> seg = Ref.cls(host, "com.coui.appcompat.segmentbutton.COUISegmentButtonLayout");
        if (seg == null) return;
        Method m = Ref.methodOf(seg, "dispatchTouchEvent", android.view.MotionEvent.class);
        if (m == null) {
            Logx.w(T, "COUISegmentButtonLayout#dispatchTouchEvent not found");
            return;
        }
        module.hook(m)
                .setId("bar_touch")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    if (a0 instanceof android.view.MotionEvent
                            && ((android.view.MotionEvent) a0).getActionMasked()
                            == android.view.MotionEvent.ACTION_DOWN) {
                        lastBarTouchMs = System.currentTimeMillis();
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked COUISegmentButtonLayout#dispatchTouchEvent (user tap tracking)");
    }

    // ── 5. 回到前台：把课表页的底栏/标题重新钉一遍 ─────────────────────────────

    private static void hookMainResume(XposedModule module, ClassLoader host) {
        Class<?> c = Ref.cls(host, "com.android.calendar.AllInOneActivity");
        if (c == null) return;
        Method m = Ref.methodOf(c, "onResume");
        if (m == null) return;
        module.hook(m)
                .setId("main_resume")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity a = (Activity)self;
                        if (TableEntryRoute.consume(a, a.getIntent())) return r;
                        HostTablePage.prepare(a);
                    }
                    if (self instanceof Activity && inKeepWindow()) {
                        Activity a = (Activity) self;
                        openCoursePage(a);
                        Logx.i(T, "main resume(保护窗): 课表页已重新挂回");
                        return r;
                    }
                    if (self instanceof Activity && HostTablePage.showing()) {
                        Activity a = (Activity) self;
                        openCoursePage(a);
                        Logx.i(T, "main resume: 课表页仍在，底栏/标题已重钉");
                    }
                    return r;
                });
        Logx.i(T, "hooked AllInOneActivity#onResume");
    }

    private static void hookMainLifecycle(XposedModule module, ClassLoader host) {
        Class<?> main = Ref.cls(host, TableEntryRoute.MAIN);
        if (main == null) return;
        Method save = Ref.methodOf(main, "onSaveInstanceState", android.os.Bundle.class);
        if (save != null) module.hook(save).setId("main_course_save")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    HostCourseImport.save((Activity)chain.getThisObject(), (android.os.Bundle)chain.getArg(0));
                    if (HostTablePage.activity() == chain.getThisObject() && HostTablePage.showing()) {
                        android.os.Bundle state = (android.os.Bundle)chain.getArg(0);
                        state.putBoolean(TableEntryRoute.OPEN, true);
                        state.putInt("lspx_course_week", HostTablePage.savedWeekIndex0());
                        state.putLong("lspx_course_monday", WeekFinishSchedule.mondayOf(java.time.LocalDate.now()).toEpochDay());
                        state.putLong("lspx_course_calendar", HostTableData.currentTableId((Activity)chain.getThisObject()));
                    }
                    return result;
                });
        Method create = Ref.methodOf(main, "onCreate", android.os.Bundle.class);
        if (create != null) module.hook(create).setId("main_course_restore")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    android.os.Bundle state = (android.os.Bundle)chain.getArg(0);
                    HostCourseImport.restore((Activity)chain.getThisObject(), state);
                    if (state != null && state.getBoolean(TableEntryRoute.OPEN, false)) {
                        Activity activity = (Activity)chain.getThisObject();
                        Intent intent = activity.getIntent();
                        if (intent != null && !intent.getBooleanExtra(TableEntryRoute.OPEN, false)) {
                            intent.putExtra(TableEntryRoute.OPEN, true);
                            long monday = WeekFinishSchedule.mondayOf(java.time.LocalDate.now()).toEpochDay();
                            if (state.getLong("lspx_course_monday", WeekFinishSchedule.NONE) == monday
                                    && state.getLong("lspx_course_calendar", -1) == HostTableData.currentTableId(activity))
                                intent.putExtra("selected_week", state.getInt("lspx_course_week", -1));
                            else intent.removeExtra("selected_week");
                        }
                    }
                    return result;
                });
        Method newIntent = Ref.methodOf(main, "onNewIntent", Intent.class);
        if (newIntent != null) module.hook(newIntent).setId("main_course_intent")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Intent intent = (Intent)chain.getArg(0);
                    if (intent != null && intent.getBooleanExtra(TableEntryRoute.OPEN, false)) keepTableMode();
                    Object result = chain.proceed();
                    TableEntryRoute.consume((Activity)chain.getThisObject(), intent);
                    return result;
                });
        Method destroy = Ref.methodOf(main, "onDestroy");
        if (destroy != null) module.hook(destroy).setId("main_course_destroy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Activity activity = (Activity)chain.getThisObject();
                    if (HostTablePage.activity() == activity) {
                        if (BarMode.active) BarMode.exit(activity);
                        HostTablePage.destroy(activity);
                        keepTableUntil = 0;
                    }
                    return chain.proceed();
                });
    }

    // ── 6. 主题选图回调（从主页发起，不是从课表 Activity） ────────────────────

    private static void hookMainActivityResult(XposedModule module, ClassLoader host) {
        Class<?> c = Ref.cls(host, "com.android.calendar.AllInOneActivity");
        if (c == null) return;
        Method m = Ref.methodOf(c, "onActivityResult", int.class, int.class, Intent.class);
        if (m == null) return;
        module.hook(m)
                .setId("main_activity_result")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    Object a1 = chain.getArg(1);
                    Object a2 = chain.getArg(2);
                    if (chain.getThisObject() instanceof Activity
                            && HostCourseImport.result((Activity)chain.getThisObject(), (Integer)a0, (Integer)a1, (Intent)a2))
                        return null;
                    if (a0 instanceof Integer && (Integer) a0 == ThemeBg.REQ_PICK
                            && chain.getThisObject() instanceof Activity) {
                        Activity act = (Activity) chain.getThisObject();
                        boolean ok = (Integer) a1 == Activity.RESULT_OK && a2 instanceof Intent;
                        Logx.i(T, "theme pick result req=" + a0 + " res=" + a1 + " ok=" + ok);
                        if (ok) {
                            android.net.Uri u = ((Intent) a2).getData();
                            if (u != null) {
                                boolean done = ThemeBg.onPicked(act, u);
                                CurriculumIo.toast(act, done ? "背景已应用" : "这张图片读取失败");
                            }
                        }
                        return null;
                    }
                    // 新建/编辑课程页回来了：刷新课表页（数据已写进宿主 Provider）
                    if (a0 instanceof Integer && (Integer) a0 == CourseEdit.REQ_EDIT
                            && chain.getThisObject() instanceof Activity) {
                        Logx.i(T, "course edit result res=" + a1);
                        if (HostTablePage.showing()) {
                            HostTablePage.refresh();
                            BarMode.refreshBackdrop((Activity) chain.getThisObject());
                        }
                        return null;
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked AllInOneActivity#onActivityResult");
    }

    // ── 顶栏标题 ───────────────────────────────────────────────────────────


    /**
     * 课表页顶栏：主页的 appbar（日期标题 + 月/周胶囊 + 副标题）在课表页没有意义，直接收起；
     * 课表页自己画一行「课程表」标题（见 HostTablePage）。离开时原样放回。
     *
     * 收的是一组而不是一个：appbar 收起后，同级的 title_layout（tv_title「待办」/「日历」）会被
     * 顶到屏幕最上面，正好压在我们自己那行标题上（实测重叠），所以它和副标题一起收。
     *
     * 为什么不去改宿主标题文字：主页标题由 MainTitleController 维护，切页/回前台时会刷回去，
     * 改文字等于和宿主抢同一块状态；收起整块反而稳定。
     */
    private static final String[] TITLE_VIEWS = {
            "calendar_appBarLayout", "title_layout", "sub_title_layout", "title_divider", "fl_data_picker2"
    };

    private static final java.util.WeakHashMap<View, Integer> titleVisibility = new java.util.WeakHashMap<>();

    private static void applyTitle(Activity activity, boolean table) {
        // Resolve the final layout once. Height animations used to resize the pager,
        // background crop and every native material surface on each frame.
        for (String name : TITLE_VIEWS) {
            View view = activity.findViewById(id(activity, name));
            if (view == null) continue;
            if (table) {
                if (!titleVisibility.containsKey(view)) titleVisibility.put(view, view.getVisibility());
                view.setVisibility(View.GONE);
            } else {
                Integer original = titleVisibility.remove(view);
                if (original != null) view.setVisibility(original);
            }
        }
    }

    private static int id(Activity a, String name) {
        return a.getResources().getIdentifier(name, "id", a.getPackageName());
    }

    /** 深色模式（宿主报告过 isNightMode 与实际不一致，这里只看系统 uiMode） */
    static boolean isNight(Activity a) {
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}













