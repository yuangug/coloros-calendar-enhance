package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 主页里的「课程表」页 —— **不重绘**，直接用宿主自己的课表视图：
 *
 *   ViewPager2（宿主 androidx）＋ 宿主 CourseWeekViewPagerAdapter ＋ 宿主 TableView
 *
 * 数据也走宿主同一口径（{@link HostTableData}：calendars_json_extensions + events_json_extensions），
 * 所以画出来和宿主课表页一模一样（周次、配色、非本周置灰、节次时间全部由宿主代码负责）。
 *
 * 页面只是往日历主页的 fragment_container 里叠一层：底栏（日历｜待办｜课程表）本来就在容器外面，
 * 所以切到课表后底栏还在，和「待办」是同一个层级的页面切换，不再打开任何 Activity。
 */
final class HostTablePage {
    private static final String T = CourseAlarmEntry.TAG;
    static final String TAG_VIEW = "lspx_table_page";
    static final String TAG_BG = "lspx_table_page_bg";
    static final String TAG_SCRIM = "lspx_table_page_scrim";

    private static Page page;
    private static final java.util.concurrent.ThreadPoolExecutor LOADER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> new Thread(r, "lspx-table-load"));
    private static final java.util.concurrent.ExecutorService CANCELLER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "lspx-table-cancel"));

    private HostTablePage() {
    }

    static boolean showing() {
        return page != null && page.visible && page.root != null && page.root.getParent() != null;
    }

    static Activity activity() {
        return page == null ? null : page.act;
    }

    /** 课表页根视图（液态玻璃要拿它算「背景图 CENTER_CROP」的映射） */
    static View rootView() {
        return page == null ? null : page.root;
    }
    static View headerView() { return page == null ? null : page.head; }

    // ── 显示 / 隐藏 ─────────────────────────────────────────────────────────

    /** Prepare data without building an invisible pager on the UI thread. */
    static void prepare(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        if (page != null && page.act == a) return;
        if (page != null) detach();
        page = new Page(a);
        page.observe();
        page.reload();
    }

    static void destroy(Activity a) {
        if (page != null && page.act == a) detach();
    }

    static void show(Activity a) {
        if (a == null) return;
        ViewGroup host = container(a);
        if (host == null) {
            Logx.w(T, "tablepage: fragment_container 未找到");
            return;
        }
        prepare(a);
        Page p = page;
        if (p.firstShowAt == 0) p.firstShowAt = android.os.SystemClock.uptimeMillis();
        boolean reused = p.root != null;
        if (p.root == null) p.mount(host);
        else if (p.root.getParent() != host) {
            if (p.root.getParent() instanceof ViewGroup) ((ViewGroup)p.root.getParent()).removeView(p.root);
            host.addView(p.root, new FrameLayout.LayoutParams(-1, -1));
        }
        p.visible = true;
        p.root.animate().cancel();
        p.root.setVisibility(View.VISIBLE);
        p.root.bringToFront();
        p.root.setAlpha(1f);
        if (!ThemeBg.signature(a).equals(p.themeKey)) p.applyTheme();
        p.refreshForClockAndConfiguration();
        refreshIfTableChanged(a);
        if (p.dirty) p.reload();
        if (p.needsBind && p.data != null && p.data.sourceTableId == HostTableData.currentTableId(a)) p.bind(p.data, p.courses);
        else if (!p.loading && p.loadFailed && p.pager == null) p.showEmpty("课表读取失败\n点这里重试");
        // HWUI may discard hidden foreground nodes. Record them before the next touch.
        invalidateTree(p.root);
        Logx.i(T, "tablepage: shown reused=" + reused + " loading=" + p.loading
                + " page=" + System.identityHashCode(p) + " pager=" + System.identityHashCode(p.pager));
    }

    static void hide() {
        final Page p = page;
        if (p == null || p.root == null) return;
        CourseDragController.cancelActive(p.act);
        p.rememberWeek();
        p.visible = false;
        p.root.animate().cancel();
        p.root.setVisibility(View.GONE);
        Logx.i(T, "tablepage: hidden, retaining pager");
    }

    private static void detach() {
        Page old = page;
        page = null;
        if (old == null) return;
        TableViewBridge.release(old.act);
        old.visible = false;
        old.generation++;
        old.cancelLoad();
        old.ui.removeCallbacksAndMessages(null);
        if (old.root != null && old.root.getViewTreeObserver().isAlive())
            old.root.getViewTreeObserver().removeOnPreDrawListener(old.keepOnTop);
        try { old.act.getContentResolver().unregisterContentObserver(old.observer); } catch (Exception ignored) { }
        try {
            ViewGroup parent = old.root == null ? null : (ViewGroup) old.root.getParent();
            if (parent != null) parent.removeView(old.root);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 课表管理里切换了「当前课表」→ 回到主页时这里把页面重载成新表。
     * 只看宿主 prefs 里的 sp_key_current_course_table，没变就不动（避免每次回前台都重建 pager）。
     */
    static void refreshIfTableChanged(Activity a) {
        if (!showing() || a == null) return;
        long now = HostTableData.currentTableId(a);
        if (now != page.selectedId) {
            Logx.i(T, "课表已切换（" + page.selectedId + " → " + now + "），重载页面");
            page.selectedId = now;
            page.cancelLoad();
            page.viewWeekIndex = -1;   // 换课表 → 回到本周
            page.pager = null;
            if (page.pagerView != null) page.pagerView.setVisibility(View.INVISIBLE);
            page.markDirty();
        }
    }

    /** Hidden pages remember invalidation; visible pages coalesce provider notifications. */
    static void refresh() {
        if (page != null) page.markDirty();
    }

    static void applyTheme() {
        if (showing()) page.applyTheme();
    }

    /** 只是重画（切凝光开关 / 换遮罩用），不重建页面、不重读数据 */
    static void repaint() {
        if (!showing() || page.root == null) return;
        // 注意：只 invalidate(root) 不会让宿主 TableView 重录 display list（子 View 的显示列表各自缓存），
        // 实测「切了经典还是玻璃卡」。必须整棵子树一起作废。
        page.root.post(() -> invalidateTree(page.root));
    }

    private static void invalidateTree(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    static void gotoToday() {
        if (!showing()) return;
        page.viewWeekIndex = -1;                 // 回到「跟随本周」
        page.gotoWeek(page.data == null ? 1 : page.data.currentWeek());
    }

    /** 宿主周次面板选完回调（0 起的周序号）→ 跳到对应周 */
    static void jumpTo(int week) {
        if (!showing()) return;
        page.gotoWeek(week);
    }

    static void openWeekIndex(int week) {
        if (page == null || week < 0) return;
        if (page.data == null || page.pager == null) page.viewWeekIndex = week;
        else page.gotoWeek(week + 1);
    }

    static void showWeekPicker(View anchor) {
        if (!showing()) return;
        page.weekPicker(anchor);
    }

    /** 宿主课表里点左上角「第 N 周 ▾」→ 走同一个周次面板 */
    static void showWeekSheet() {
        if (!showing()) return;
        page.weekPicker(null);
    }

    /** 当前课表数据（课程详情面板补时间用） */
    static HostTableData currentData() {
        return page == null ? null : page.data;
    }

    /**
     * 用户此刻看的是哪一周（0 起）：选过周次就用那一周，否则跟随「本周」。
     * 分享课表要把这个值当成宿主的 selected_week 传过去，否则分享图停在别的周。
     */
    static int viewingWeekIndex0() {
        if (!showing() || page == null) return -1;
        page.rememberWeek();
        if (page.viewWeekIndex >= 0) return page.viewWeekIndex;
        return page.data == null ? -1 : page.data.currentWeekIndex0();
    }

    static void showMenu(View anchor) {
        if (!showing()) return;
        page.menu(anchor);
    }

    /** ease-out-cubic：接近宿主 spring 的收尾手感，但不过冲 */
    private static android.view.animation.Interpolator easeOut() {
        return new android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1f);
    }

    private static ViewGroup container(Activity a) {
        int id = a.getResources().getIdentifier("fragment_container", "id", a.getPackageName());
        View v = a.findViewById(id);
        return v instanceof ViewGroup ? (ViewGroup) v : null;
    }

    // ── 页面本体 ───────────────────────────────────────────────────────────

    private static final class Page {
        final Activity act;
        final ClassLoader host;
        FrameLayout root;
        FrameLayout content;
        TextView head;
        TextView empty;
        View pagerView;
        Object pager;
        HostTableData data;
        List<Object> courses;
        int generation;
        boolean visible, loading, dirty = true, needsBind;
        boolean refreshScheduled, loadFailed;
        int loadCount, bindCount;
        long lastLoadMs;
        long firstLoadMs, maxLoadMs, firstShowAt;
        LoadTask activeLoad;
        long selectedId;
        String themeKey;
        String boundDay;
        int boundConfiguration, boundCurrentWeek;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable refreshData = () -> {
            refreshScheduled = false;
            if ((visible || root == null) && dirty && page == this) reload();
        };
        final android.database.ContentObserver observer = new android.database.ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) { markDirty(); }
        };
        final android.view.ViewTreeObserver.OnPreDrawListener keepOnTop = this::keepOnTop;
        private boolean keepOnTop() {
            if (!visible || root == null || page != this) return true;
            boolean changed = MainTabHook.enforceCourseTitle(act);
            BarMode.syncAccessories(act);
            if (root.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup)root.getParent();
                if (parent.indexOfChild(root) != parent.getChildCount() - 1) {
                    // Cold starts can commit the native calendar/todo Fragment after
                    // onResume. Keep it behind our page without changing its lifecycle.
                    root.bringToFront();
                    BarMode.enter(act);
                    changed = true;
                }
            }
            return !changed;
        }

        Page(Activity a) {
            this.act = a;
            this.host = a.getClassLoader();
            selectedId = HostTableData.currentTableId(a);
        }

        void observe() {
            for (String authority : new String[]{"com.android.calendar", "com.coloros.calendar"}) {
                for (String path : new String[]{"events", "calendars", "reminders"}) {
                    try { act.getContentResolver().registerContentObserver(
                            android.net.Uri.parse("content://" + authority + "/" + path), true, observer); }
                    catch (Exception error) { Logx.w(T, "table observer: " + error); }
                }
            }
        }

        void markDirty() {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                ui.post(this::markDirty);
                return;
            }
            if (page != this) return;
            rememberWeek();
            dirty = true;
            generation++;
            scheduleRefresh();
        }

        private void scheduleRefresh() {
            // A notification burst must not keep pushing the first read into the future.
            if (!loading && !refreshScheduled && dirty && (visible || root == null)) {
                refreshScheduled = true;
                ui.postDelayed(refreshData, visible ? 100 : 300);
            }
        }

        private String dayKey() {
            java.util.Calendar day = java.util.Calendar.getInstance();
            return day.get(java.util.Calendar.YEAR) + ":" + day.get(java.util.Calendar.DAY_OF_YEAR)
                    + ":" + day.getTimeZone().getID();
        }

        void refreshForClockAndConfiguration() {
            if (boundDay == null) return;
            boolean dayChanged = !boundDay.equals(dayKey());
            if (dayChanged || boundConfiguration != act.getResources().getConfiguration().hashCode()) {
                rememberWeek();
                if (dayChanged && viewWeekIndex == boundCurrentWeek) { viewWeekIndex = -1; pager = null; }
                markDirty();
            }
        }

        void rememberWeek() {
            if (pager == null) return;
            Object value = Ref.callName(pager, "getCurrentItem");
            if (value instanceof Number) viewWeekIndex = ((Number)value).intValue();
        }

        void mount(ViewGroup parent) {
            root = new FrameLayout(act);
            root.setTag(TAG_VIEW);
            root.setClickable(true);
            root.setBackgroundColor(isNight() ? 0xFF101216 : 0xFFF7F8FA);
            root.setClipChildren(false);

            // 顶栏：主页的 appbar 在课表页被收起（见 MainTabHook#applyTitle），这里自己给一行标题
            final int headerH = dp(52);
            head = new TextView(act);
            head.setText("课程表");
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setTextColor(isNight() ? 0xFFF0F1F3 : 0xFF1A1A1A);
            head.setPadding(dp(20), 0, dp(20), 0);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, headerH);
            hlp.gravity = Gravity.TOP;
            root.addView(head, hlp);

            content = new FrameLayout(act);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            clp.topMargin = headerH;
            root.addView(content, clp);

            empty = new TextView(act);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(isNight() ? 0xFF9AA0AB : 0xFF7A808C);
            empty.setText("正在读取课表…");
            empty.setVisibility(View.VISIBLE);
            empty.setOnClickListener(v -> {
                if (loading) {
                    if (activeLoad == null || !activeLoad.retryOffered) return;
                    cancelLoad();
                    reload();
                    return;
                }
                if (loadFailed) {
                    reload();
                } else if (data == null || data.tableJson == null) {
                    CurriculumIo.showImportMenu(act);
                } else {
                    reload();
                }
            });
            FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            elp.topMargin = headerH;
            root.addView(empty, elp);

            applyTheme();
            parent.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.getViewTreeObserver().addOnPreDrawListener(keepOnTop);
            Logx.i(T, "tablepage mounted");
        }

        boolean isNight() {
            return (act.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }

        // ── 主题 ───────────────────────────────────────────────────────────

        void applyTheme() {
            if (root == null) return;
            themeKey = ThemeBg.signature(act);
            // 清掉旧层（背景层 + 遮罩层都挂在 root 上，和 pager 做兄弟，保证在课表之下）
            for (String tag : new String[]{TAG_BG, TAG_SCRIM}) {
                for (ViewGroup g : new ViewGroup[]{root, content}) {
                    if (g == null) continue;
                    View old = g.findViewWithTag(tag);
                    if (old != null) g.removeView(old);
                }
            }
            int type = ThemeStore.type(act);
            root.setBackground(new ThemeWindowBackground(root, false));
            // 图片主题：让宿主课表的空格子变磨砂（详见 ThemeBg#imageActive）
            ThemeBg.applyTokens(act);
            if (head != null) head.setTextColor(headerInk(type));
            CourseSurfaceRenderer.invalidateHeader(root);

            boolean hasLayer = false;
            if (type == ThemeStore.TYPE_IMAGE) {
                View layer = ThemeBg.layer(act);
                if (layer != null) {
                    layer.setTag(TAG_BG);
                    // 挂在页面根上（不是 content）：content 底部有给底栏留的 padding，挂 content 时那块会露出白底，
                    // 看起来就是「底部一条白边」。挂 root 则整页（含顶部标题、底部留白）都被背景覆盖。
                    root.addView(layer, 0, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    hasLayer = true;
                }
            }

            // 背景遮罩：**所有主题**都铺一层（以前只在图片主题里加，纯色/渐变点了没反应），
            // 位置在背景之上、顶栏和课表之下 —— 空格子已经是半透明（NingGuang#emptyCellTint），
            // 所以这一层真的能看见浓度变化。
            int scrim = ThemeStore.scrim(act);
            if (scrim > 0 && type != ThemeStore.TYPE_NONE) {
                View s = new View(act);
                s.setTag(TAG_SCRIM);
                s.setBackgroundColor(Color.argb(scrim, isNight() ? 0 : 255,
                        isNight() ? 0 : 255, isNight() ? 0 : 255));
                root.addView(s, hasLayer ? 1 : 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            Logx.i(T, "tablepage theme applied type=" + type + " scrim=" + scrim
                    + " 凝光=" + ThemeStore.ningguang(act) + " (" + NingGuang.describe(act) + ")");
        }

        // ── 数据 ───────────────────────────────────────────────────────────

        void reload() {
            if (loading) return;
            ui.removeCallbacks(refreshData);
            refreshScheduled = false;
            loading = true;
            dirty = false;
            loadFailed = false;
            loadCount++;
            if (empty != null) empty.setText("正在读取课表…");
            LoadTask task = new LoadTask();
            activeLoad = task;
            ui.postDelayed(task.timeout, 10_000);
            try { task.future = LOADER.submit(task); }
            catch (Throwable error) { finishLoad(task, null, null, error); }
        }

        private final class LoadTask implements Runnable {
            final android.os.CancellationSignal cancel = new android.os.CancellationSignal();
            final long requestedId = HostTableData.currentTableId(act);
            final long started = android.os.SystemClock.elapsedRealtime();
            volatile String phase = "queued";
            boolean retryOffered;
            java.util.concurrent.Future<?> future;
            final Runnable timeout = () -> {
                if (activeLoad != this) return;
                // A slow provider may be about to reply. Keep that live query and
                // publish its result; restart only if the user actually asks us to.
                retryOffered = true;
                if (empty != null && empty.getVisibility() == View.VISIBLE)
                    empty.setText("课表读取较慢，点这里重试");
                Logx.w(T, "slow table read phase=" + phase);
            };

            void cancelWork() {
                if (future != null) future.cancel(true);
                LOADER.purge();
                // Remote provider cancellation can block. Never perform that Binder call on UI.
                CANCELLER.execute(cancel::cancel);
            }

            public void run() {
                HostTableData loaded = null;
                List<Object> result = null;
                Throwable failure = null;
                try {
                    cancel.throwIfCanceled();
                    phase = "provider";
                    loaded = HostTableData.load(act, cancel);
                    phase = "entities";
                    result = loaded.buildCourseList(host);
                    cancel.throwIfCanceled();
                } catch (Throwable error) { failure = error; }
                final HostTableData d = loaded;
                final List<Object> rows = result;
                final Throwable error = failure;
                phase = "publish";
                ui.post(() -> finishLoad(this, d, rows, error));
            }
        }

        void cancelLoad() {
            LoadTask old = activeLoad;
            activeLoad = null;
            loading = false;
            if (old != null) {
                ui.removeCallbacks(old.timeout);
                old.cancelWork();
            }
        }

        private void finishLoad(LoadTask task, HostTableData d, List<Object> result, Throwable error) {
            if (activeLoad != task) return;
            activeLoad = null;
            loading = false;
            ui.removeCallbacks(task.timeout);
            lastLoadMs = android.os.SystemClock.elapsedRealtime() - task.started;
            if (firstLoadMs == 0) firstLoadMs = lastLoadMs;
            maxLoadMs = Math.max(maxLoadMs, lastLoadMs);
            if (page != this || act.isDestroyed()) return;
            long current = HostTableData.currentTableId(act);
            if (current != task.requestedId || (d != null && d.sourceTableId != current)) {
                dirty = true;
                scheduleRefresh();
                return;
            }
            if (error != null) {
                loadFailed = true;
                // An error is not an empty table. Keep a usable old page and offer a retry.
                dirty = false;
                Logx.e(T, "tablepage: read failed after " + lastLoadMs + "ms", error);
                if (empty != null && (pager == null || data == null || data.sourceTableId != current))
                    showEmpty("课表读取失败\n点这里重试");
                else if (visible) CurriculumIo.toast(act, "课表刷新失败，请稍后重试");
                return;
            }
            try {
                if (d.calendarId > 0) TableSettings.refreshLoaded(act, d.calendarId, d.calendar,
                        CalendarBackend.LOCAL.equals(d.backendAuthority));
            } catch (Exception settingsError) {
                TableSettings.invalidate(d.calendarId);
                Logx.w(T, "课表交互设置未就绪: " + settingsError);
            }
            boolean unchanged = d.sameContents(data);
            loadFailed = false;
            rememberWeek();
            data = d;
            courses = result;
            boolean clockChanged = !dayKey().equals(boundDay)
                    || boundConfiguration != act.getResources().getConfiguration().hashCode();
            needsBind |= !unchanged || pager == null || clockChanged;
            if (visible && needsBind) bind(d, courses);
            else if (visible) hideEmpty();
            // Publish a complete same-table snapshot even if another notification arrived.
            // A single follow-up read converges on the latest data without starving first paint.
            scheduleRefresh();
            Logx.i(T, "tablepage: read " + lastLoadMs + "ms unchanged=" + unchanged + " pending=" + dirty);
        }

        private void bind(HostTableData d, List<Object> courses) {
            bindCount++;
            needsBind = false;
            boundDay = dayKey();
            boundConfiguration = act.getResources().getConfiguration().hashCode();
            boundCurrentWeek = d.currentWeekIndex0();
            // 换数据：先把旧 pager 摘掉
            if (pagerView != null) {
                content.removeView(pagerView);
                pagerView = null;
                pager = null;
            }
            // 不再给底栏留 padding：课表要一直铺到底，让它从悬浮底栏下面透出来
            //（底栏的底衬在课表页是透明的，见 BarMode#setBarBackdrop）
            content.setPadding(0, 0, 0, 0);

            if (d.tableJson == null) {
                showEmpty("这台手机上还没找到课程表\n点这里导入 / 新建");
                return;
            }
            DataBound bound = new DataBound(d, courses);
            if (!bound.attach()) {
                showEmpty("宿主课表视图不可用\n点这里重试（详见 COURSEALARM 日志）");
                return;
            }
            hideEmpty();
        }

        /** 把宿主适配器塞进 ViewPager2 —— 全部反射，模块工程不引 androidx */
        private final class DataBound {
            final HostTableData d;
            final List<Object> courses;

            DataBound(HostTableData d, List<Object> courses) {
                this.d = d;
                this.courses = courses;
            }

            boolean attach() {
                Class<?> vp2 = Ref.cls(host, "androidx.viewpager2.widget.ViewPager2");
                Class<?> adapterBase = Ref.cls(host, "androidx.recyclerview.widget.RecyclerView$Adapter");
                if (vp2 == null || adapterBase == null) {
                    Logx.w(T, "tablepage: vp2=" + (vp2 != null) + " base=" + (adapterBase != null)
                            + "，无法创建周课表");
                    return false;
                }
                try {
                    View pv = (View) vp2.getConstructor(Context.class).newInstance(act);
                    pv.setBackground(null);
                    Constructor<?> k = HostCompat.weekAdapter(host);
                    Object ad = k.newInstance(act, d.tableJson, courses,
                            Math.max(1, d.weekCount), false, d.currentWeekIndex0());
                    Logx.i(T, "tablepage: 适配器已建 " + ad.getClass().getName());
                    Ref.callName(pv, "setOffscreenPageLimit", 1);
                    Ref.callName(pv, "setOrientation", 0);
                    Ref.callName(pv, "setAdapter", ad);

                    // 关键：**上屏之前**先定好当前周。ViewPager2 会把它存成 pendingCurrentItem，
                    // 首次 layout 直接落在这一页 —— 否则会先画出第 1 周再跳过来（肉眼就是闪一下）。
                    // （宿主把 setCurrentItem 的名字也混淆了，按形参类型 (int, boolean) 找）
                    // viewWeekIndex >= 0 表示用户刚选过周次：按那一周建页，和首屏是同一路径。
                    final int idx = viewWeekIndex >= 0
                            ? Math.max(0, Math.min(viewWeekIndex, Math.max(0, d.weekCount - 1)))
                            : d.currentWeekIndex0();
                    HostCompat.pagerCurrentItem(vp2).invoke(pv, idx, false);
                    content.addView(pv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    Logx.i(T, "tablepage: 首屏定位 weekIndex=" + idx);
                    pagerView = pv;
                    pager = pv;
                    adapter = ad;
                    Logx.i(T, "tablepage: 宿主课表已挂载 weeks=" + d.weekCount
                            + " courses=" + courses.size() + " weekIndex=" + d.currentWeekIndex0()
                            + " adapter=" + k.getDeclaringClass().getName());
                    return true;
                } catch (Throwable t) {
                    Logx.e(T, "tablepage: 挂载宿主课表失败", t);
                    return false;
                }
            }

        }

        private Object adapter;

        private void showEmpty(String text) {
            empty.setText(text);
            empty.setVisibility(View.VISIBLE);
            if (pagerView != null) pagerView.setVisibility(View.GONE);
        }

        private void hideEmpty() {
            empty.setVisibility(View.GONE);
            if (pagerView != null) pagerView.setVisibility(View.VISIBLE);
        }

        // ── 周次 ───────────────────────────────────────────────────────────

        /**
         * 当前看的是哪一周（0 起）；-1 = 跟随「本周」。
         * 跳周走的是「按目标周重建页面」，和宿主 SchoolTimeTableActivity 选完周次后 refreshCourseData()
         * 是同一个做法 —— 比在已有 ViewPager2 上 setCurrentItem 稳：
         * 后者虽然能跳过去，但宿主的课表里时间列/日列是几块联动滚动区，跳完之后会残留几十像素的
         * 横向偏移（左边「11周」和时间被切掉一截），重建就没有这个问题。
         */
        int viewWeekIndex = -1;

        void gotoWeek(int week) {
            if (data == null) return;
            rememberWeek();
            int idx = Math.max(0, Math.min(week - 1, Math.max(0, data.weekCount - 1)));
            boolean same = idx == (viewWeekIndex >= 0 ? viewWeekIndex : data.currentWeekIndex0());
            if (same) {
                // 已经在目标周：只做一次轻量对齐，不重建
                if (pager != null) pagerTo(pager, idx, false);
                Logx.i(T, "tablepage: 已在第 " + week + " 周，无需跳转");
                return;
            }
            viewWeekIndex = idx;
            pager = null; // Provider notifications must not replace the requested week with the old pager's position.
            Logx.i(T, "tablepage: jump week " + week + " (index " + idx + ") → 按目标周重建");
            // Keep the host's alignment-safe pager rebuild, but reuse the loaded snapshot.
            if (courses != null && data.sourceTableId == HostTableData.currentTableId(act)) bind(data, courses);
            else reload();
        }

        /**
         * 跳到某一页。**不能按名字调**：宿主把 ViewPager2#setCurrentItem 混淆成了 m，
         * 之前「回到本周 / 选择周次」点了没反应就是卡在这（两个版本都一样）。
         * 使用已核对的公开入口 setCurrentItem/m，避免误选同签名的内部 n 方法。
         */
        private static boolean pagerTo(Object pv, int idx, boolean smooth) {
            try {
                HostCompat.pagerCurrentItem(pv.getClass()).invoke(pv, idx, smooth);
                return true;
            } catch (Throwable t) {
                Logx.w(T, "tablepage: setCurrentItem 调用失败 " + t);
            }
            return false;
        }

        void weekPicker(View anchor) {
            if (data == null) {
                reload();
                return;
            }
            // 宿主原生的「选周次」面板（COUI 底部面板 + 周网格 + 本周标记），样式和独立课表页一模一样
            if (HostWeekSheet.show(act, host, data)) {
                Logx.i(T, "tablepage: 周次选择 → 宿主原面板 CurrentWeekSelectFragment");
                return;
            }
            Logx.w(T, "tablepage: 宿主周次面板不可用，回落自绘面板");
            final Dialog dlg = new Dialog(act);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.3f);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setGravity(Gravity.BOTTOM);
            }
            boolean night = isNight();
            int bgColor = night ? 0xFF1C1F25 : 0xFFFFFFFF;
            int cardColor = night ? 0xFF2A2E36 : 0xFFF2F3F7;
            int fg = night ? 0xFFF0F1F3 : 0xFF1A1A1A;
            int accent = accentColor();

            LinearLayout sheet = new LinearLayout(act);
            sheet.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
            sheet.setBackground(bg);
            sheet.setPadding(dp(20), dp(12), dp(20), dp(20) + navBar());

            FrameLayout head = new FrameLayout(act);
            head.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            TextView cancel = new TextView(act);
            cancel.setText("取消");
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cancel.setTextColor(fg);
            cancel.setGravity(Gravity.CENTER);
            GradientDrawable cbg = new GradientDrawable();
            cbg.setColor(cardColor);
            cbg.setCornerRadius(dp(18));
            cancel.setBackground(cbg);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(68), dp(34));
            clp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            cancel.setLayoutParams(clp);
            cancel.setOnClickListener(v -> dlg.dismiss());
            head.addView(cancel);
            TextView title = new TextView(act);
            title.setText("选择周次");
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

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int cur = data.currentWeek();
            int perRow = 6;
            LinearLayout row = null;
            for (int i = 1; i <= Math.max(1, data.weekCount); i++) {
                if ((i - 1) % perRow == 0) {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = dp(8);
                    wrap.addView(row, rlp);
                }
                TextView cell = new TextView(act);
                cell.setText(String.valueOf(i));
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                cell.setTextColor(i == cur ? 0xFFFFFFFF : fg);
                GradientDrawable cg = new GradientDrawable();
                cg.setCornerRadius(dp(16));
                cg.setColor(i == cur ? accent : cardColor);
                cell.setBackground(cg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.rightMargin = dp(8);
                row.addView(cell, lp);
                final int week = i;
                cell.setOnClickListener(v -> {
                    dlg.dismiss();
                    gotoWeek(week);
                });
            }
            sheet.addView(wrap);

            dlg.setContentView(sheet, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dlg.setOnShowListener(d -> {
                Window ww = dlg.getWindow();
                if (ww != null) ww.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            });
            dlg.show();
            Logx.i(T, "tablepage: week picker, weeks=" + data.weekCount);
        }

        // ── 右下 ⋮（课表页自己的菜单） ───────────────────────────────────────

        /**
         * 课表页的 ⋮：样式用宿主自己的 COUIPopupListWindow（和原生课表页那个弹窗一模一样：
         * 大圆角卡片 + 大行距 + 背景模糊），条目照搬 R.menu.school_time_table_menu，再接本模块的几项。
         * 取不到 COUI 弹窗时退回系统 PopupMenu（功能不受影响，只是样式朴素）。
         */
        private Object menuWindow;

        void menu(View anchor) {
            try {
                Class<?> winCls = Ref.clsStrict(host, "com.coui.appcompat.poplist.COUIPopupListWindow");
                Class<?> itemCls = Ref.clsStrict(host, "com.coui.appcompat.poplist.PopupListItem");
                Object win = winCls.getConstructor(Context.class).newInstance(act);
                java.util.List<Object> items = new java.util.ArrayList<>();
                java.util.List<String> titles = java.util.Arrays.asList(TITLES);
                for (int i = 0; i < titles.size(); i++) {
                    // 照宿主的写法用 Builder（裸构造器缺 itemType/groupId，show() 会抛）
                    Object b = menuBuilder(itemCls);
                    Ref.callName(b, "setId", MENU_IDS[i]);
                    Ref.callName(b, "setTitle", titles.get(i));
                    Ref.callName(b, "setGroupId", 1);
                    items.add(Ref.callName(b, "build"));
                }

                Ref.callName(win, "setItemList", items);
                Ref.callName(win, "setUseBackgroundBlur", Boolean.TRUE);
                java.lang.reflect.Method set = Ref.methodOf(winCls, "setOnItemClickListener",
                        android.widget.AdapterView.OnItemClickListener.class);
                if (set != null) {
                    set.invoke(win, (android.widget.AdapterView.OnItemClickListener)
                            (parent, view, position, id) -> { if (position >= 0 && position < MENU_IDS.length) onMenuPick(MENU_IDS[position]); });
                }
                menuWindow = win;   // 持住引用，弹窗被 GC 会直接消失
                Ref.callName(win, "show", anchor);
                Logx.i(T, "tablepage: COUI 菜单 shown (" + items.size() + " items)");
            } catch (Throwable t) {
                Logx.w(T, "COUI 弹窗不可用，退回系统 PopupMenu: " + t + " cause=" + t.getCause());
                fallbackMenu(anchor);
            }
        }

        private static final int MENU_TABLE_SETTINGS = 11;
        private static final int[] MENU_IDS = {1, 2, 3, 4, MENU_TABLE_SETTINGS, 5, 6, 7, 8, 9, 10};
        private static final String[] TITLES = {"创建课表", "课表管理", "分享课表", "课程提醒", "课表设置",
                "创建桌面快捷方式", "导入课表", "导出课表", "课表主题", "回到本周", "刷新课表"};

        private Object menuBuilder(Class<?> itemCls) throws Exception {
            Class<?> builderCls = Ref.clsStrict(host, "com.coui.appcompat.poplist.PopupListItem$Builder");
            return builderCls.getConstructor().newInstance();
        }

        private void fallbackMenu(View anchor) {
            PopupMenu pm = new PopupMenu(act, anchor);
            String[] titles = TITLES;
            for (int i = 0; i < titles.length; i++) pm.getMenu().add(0, MENU_IDS[i], i, titles[i]);
            pm.setOnMenuItemClickListener(item -> {
                onMenuPick(item.getItemId());
                return true;
            });
            pm.show();
            Logx.i(T, "tablepage: 系统 PopupMenu (" + titles.length + " items)");
        }

        private void onMenuPick(int id) {
            Logx.i(T, "tablepage menu pick id=" + id);
            try {
                Ref.callName(menuWindow, "dismiss");   // 弹窗不会自己收，起新页面之前先关掉
            } catch (Throwable ignored) {
            }
            switch (id) {
                case 1:
                    // 原生那个「创建课表」面板：拍照导入 / 相册导入 / 手动创建
                    // （ImportCourseTableHelper.F + dialog_create_course）；取不到才退回手动创建页
                    if (!HostCreateSheet.show(act)) {
                        Logx.w(T, "创建课表：宿主原生面板不可用，退回 CreateCourseTableActivity");
                        openHostActivity("com.android.calendar.card.schooltimetable.CreateCourseTableActivity");
                    }
                    break;
                case 2:
                    openHostActivity("com.android.calendar.card.schooltimetable.CourseTableManageActivity");
                    break;
                case 3:
                    // 分享课表：必须带宿主那五个 extras，否则分享页标题是 null、周次是第 0 周、表格空白
                    HostShareTable.start(act);
                    break;
                case 4:
                    HostEntities.openReminder(act);
                    break;
                case MENU_TABLE_SETTINGS:
                    TableSettingsPage.open(act);
                    break;
                case 5:
                    addDesktopShortcut();
                    break;
                case 6:
                    CurriculumIo.showImportMenu(act);
                    break;
                case 7:
                    CurriculumIo.showExportMenu(act);
                    break;
                case 8:
                    ThemeSheet.show(act);
                    break;
                case 9:
                    gotoToday();
                    break;
                case 10:
                    reload();
                    break;
                default:
                    break;
            }
        }
        /** 起宿主自己的课表相关 Activity（类名来自反编译，稳定） */
        private void openHostActivity(String cls) {
            try {
                Class<?> c = Ref.clsStrict(host, cls);
                Intent it = new Intent(act, c);
                act.startActivity(it);
                Logx.i(T, "tablepage: 打开宿主页面 " + cls);
            } catch (Throwable t) {
                Logx.e(T, "打开 " + cls + " 失败", t);
                CurriculumIo.toast(act, "打不开：" + t);
            }
        }

        /** 创建桌面快捷方式：照抄宿主的参数（ShortcutUtils.addShortcut） */
        private void addDesktopShortcut() {
            try {
                android.content.pm.ShortcutManager shortcuts = act.getSystemService(android.content.pm.ShortcutManager.class);
                String shortcutId = "school_time_table_activity_action";
                if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
                    CurriculumIo.toast(act, "当前桌面不支持添加快捷方式");
                    return;
                }
                for (android.content.pm.ShortcutInfo shortcut : shortcuts.getPinnedShortcuts()) {
                    if (shortcutId.equals(shortcut.getId())) {
                        CurriculumIo.toast(act, "桌面已有「课程表」快捷方式");
                        return;
                    }
                }
                int iconId = act.getResources().getIdentifier("ic_course_action", "drawable", act.getPackageName());
                Intent launch = TableEntryRoute.mainIntent(act, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(act, shortcutId)
                        .setShortLabel("课程表").setLongLabel("课程表")
                        .setIcon(android.graphics.drawable.Icon.createWithResource(act, iconId)).setIntent(launch).build();
                boolean requested = shortcuts.requestPinShortcut(info, null);
                CurriculumIo.toast(act, requested ? "已提交添加请求，请在桌面提示中确认" : "桌面未接受添加请求");
                Logx.i(T, "tablepage: addShortcut done");
            } catch (Throwable t) {
                Logx.e(T, "addShortcut 失败", t);
                CurriculumIo.toast(act, "创建快捷方式失败：" + t);
            }
        }

        // ── 尺寸 ───────────────────────────────────────────────────────────

        /** 顶栏标题颜色：深色主题（纯色/渐变偏暗）上用浅色字，否则用深色字 */
        int headerInk(int type) {
            boolean darkBg = isNight();
            if (type == ThemeStore.TYPE_SOLID) {
                int idx = Math.max(0, Math.min(ThemeStore.solidIndex(act), ThemeStore.SOLIDS.length - 1));
                darkBg = lum(ThemeStore.SOLIDS[idx]) < 0.55f;
            } else if (type == ThemeStore.TYPE_GRADIENT) {
                int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(act), ThemeStore.GRADIENTS.length - 1));
                darkBg = lum(ThemeStore.GRADIENTS[idx][0]) < 0.55f;
            }
            return darkBg ? 0xFFF0F1F3 : 0xFF1A1A1A;
        }

        float lum(int c) {
            return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
        }

        int accentColor() {
            try {
                TypedValue tv = new TypedValue();
                if (act.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                    return tv.data | 0xFF000000;
                }
            } catch (Throwable ignored) {
            }
            return 0xFF3A7AFF;
        }

        int dp(int v) {
            return Math.round(v * act.getResources().getDisplayMetrics().density);
        }

        int navBar() {
            try {
                int id = act.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (id > 0) return act.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
            return dp(16);
        }
    }
}



















