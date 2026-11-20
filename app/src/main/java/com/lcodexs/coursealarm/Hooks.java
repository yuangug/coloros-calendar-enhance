package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 业务 hook 全部集中在这里（只用反射拿 Method，不 import 宿主类）。
 * 类名/方法名与版本证据见 analysis/findings-coursealarm.md。
 */
final class Hooks {
    private static final String T = CourseAlarmEntry.TAG;

    private Hooks() {
    }

    static void install(XposedModule module, ClassLoader host, String pkg, Context app) {
        if (CourseAlarmEntry.PKG_CLOCK.equals(pkg)) {
            installClock(module, host);
        } else {
            installCalendar(module, host, app);
        }
    }

    // ── 日历进程 ──────────────────────────────────────────────────────────────
    private static void installCalendar(XposedModule module, ClassLoader host, Context app) {
        appCtx = app;
        MutationHooks.install(module, host, app);
        CourseRescheduleRepair.install(module, host);
        TableSettingsPage.install(module, host);
        CourseLiveHooks.install(module, host, app);
        CourseDetailMaterial.install(module, host);
        hookReminderPreferences(module, host);
        hookNightTick(module, host, app);
        hookCourseTableMenu(module, host);
        hookActivityResult(module, host);
        hookTableTokens(module, host);
        hookEmptyCellTint(module, host);
        CourseSurfaceRenderer.install(module, host);
        hookBlockLegacyTablePage(module, host);
        hookManageActivity(module, host);
        MainTabHook.install(module, host);
        // Device verification code is excluded from normal APKs.
        try { Class.forName("com.lcodexs.coursealarm.DeviceVerification").getMethod("schedule", Context.class, XposedModule.class).invoke(null, app, module); }
        catch (ClassNotFoundException ignored) { }
        catch (Exception e) { Logx.e(T, "device verification setup", e); }

        // 仅在任一时段启用时维持夜间排程。
        try {
            Scheduler.update(app);
            NightJob.observeTableSelection(app);
        } catch (Throwable t) {
            Logx.e(T, "ensureScheduled failed", t);
        }
    }

    /** 课程表页「更多」菜单：追加 导入课表 / 导出课表 */
    /** 菜单项注入到「主页」和「独立课表页」两处（课表已经搬进主页，主页才是主入口） */
    private static void hookCourseTableMenu(XposedModule module, ClassLoader host) {
        for (String cls : new String[]{
                "com.android.calendar.AllInOneActivity",
                "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity"}) {
            hookMenuOn(module, host, cls);
        }
    }

    private static void hookMenuOn(XposedModule module, ClassLoader host, final String cls) {
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        Method m = null;
        for (Method cand : c.getDeclaredMethods()) {
            if (cand.getName().equals("onCreateOptionsMenu") && cand.getParameterCount() == 1) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "onCreateOptionsMenu not found on " + cls);
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("course_menu")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object menuObj = chain.getArg(0);
                    Object self = chain.getThisObject();
                    if (menuObj instanceof android.view.Menu && self instanceof android.app.Activity) {
                        try {
                            injectMenuItems((android.app.Activity) self, (android.view.Menu) menuObj);
                        } catch (Throwable t) {
                            Logx.e(T, "injectMenuItems failed", t);
                        }
                    }
                    return r;
                });
        Logx.i(T, "hooked " + cls + "#onCreateOptionsMenu");
    }

    private static final int ID_IMPORT = 0x7E01;
    private static final int ID_EXPORT = 0x7E02;
    private static final int ID_THEME = 0x7E03;
    /** 宿主溢出菜单里的「课程表」项：入口已移到底栏第三段，这里隐藏掉 */
    private static final int ID_HOST_SCHOOL_TABLE = 0x7f0a070f;

    static void injectMenuItems(android.app.Activity act, android.view.Menu menu) {
        if (menu.findItem(ID_IMPORT) != null && menu.findItem(ID_EXPORT) != null) return;
        if (menu.findItem(ID_IMPORT) != null) menu.removeItem(ID_IMPORT);
        if (menu.findItem(ID_EXPORT) != null) menu.removeItem(ID_EXPORT);
        // 宿主的「课程表」菜单项 id 会随版本漂（16.24.2 = 0x7f0a070f，17.3.70 = 0x7f0a072c）
        int hostId = act.getResources().getIdentifier("school_table", "id", act.getPackageName());
        if (hostId != 0 && menu.findItem(hostId) != null) {
            menu.removeItem(hostId);
            Logx.i(T, "menu: hid host 课程表 item (id=0x" + Integer.toHexString(hostId) + ")");
        } else if (menu.findItem(ID_HOST_SCHOOL_TABLE) != null) {
            menu.removeItem(ID_HOST_SCHOOL_TABLE);
            Logx.i(T, "menu: hid host 课程表 item (legacy id)");
        }

        android.view.MenuItem imp = menu.add(android.view.Menu.NONE, ID_IMPORT, 100,
                CurriculumIo.MENU_IMPORT);
        imp.setOnMenuItemClickListener(item -> {
            Logx.i(T, "menu click 导入课表");
            CurriculumIo.showImportMenu(act);
            return true;
        });
        android.view.MenuItem exp = menu.add(android.view.Menu.NONE, ID_EXPORT, 101,
                CurriculumIo.MENU_EXPORT);
        exp.setOnMenuItemClickListener(item -> {
            Logx.i(T, "menu click 导出课表");
            CurriculumIo.showExportMenu(act);
            return true;
        });
        android.view.MenuItem theme = menu.add(android.view.Menu.NONE, ID_THEME, 102,
                "课表主题");
        theme.setOnMenuItemClickListener(item -> {
            Logx.i(T, "menu click 课表主题");
            ThemeSheet.show(act);
            return true;
        });
        Logx.i(T, "menu items injected (import/export/theme), size=" + menu.size());
    }

    /** 文件选择回调：只截我们自己的 requestCode */
    private static void hookActivityResult(XposedModule module, ClassLoader host) {
        final String cls = "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity";
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        int hooked = 0;
        // 宿主可能声明 3 参（老签名）或 4 参（Android 14 的 ComponentCaller 签名），
        // 也可能两者都有 —— 逐个类抓，直到 Activity 为止。
        for (Class<?> k = c; k != null && k != android.app.Activity.class; k = k.getSuperclass()) {
            for (Method cand : k.getDeclaredMethods()) {
                if (!cand.getName().equals("onActivityResult")) continue;
                int n = cand.getParameterCount();
                if (n != 3 && n != 4) continue;
                cand.setAccessible(true);
                module.hook(cand)
                        .setId("course_import_result_" + n)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            Object a0 = chain.getArg(0);
                            Object a1 = chain.getArg(1);
                            Object a2 = chain.getArg(2);
                            if (self instanceof android.app.Activity
                                    && a0 instanceof Integer && a1 instanceof Integer) {
                                int req = (Integer) a0;
                                if (req == ThemeBg.REQ_PICK) {
                                    Logx.i(T, "onActivityResult for theme pick res=" + a1);
                                    if ((Integer) a1 == android.app.Activity.RESULT_OK
                                            && a2 instanceof android.content.Intent) {
                                        android.net.Uri u = ((android.content.Intent) a2).getData();
                                        if (u != null) {
                                            boolean ok = ThemeBg.onPicked((android.app.Activity) self, u);
                                            CurriculumIo.toast((android.app.Activity) self,
                                                    ok ? "背景已应用" : "这个图片读取失败");
                                        }
                                    }
                                    return null;
                                }
                                if (req == CurriculumIo.REQ_ICS || req == CurriculumIo.REQ_XLSX
                                        || req == CurriculumIo.REQ_FILE) {
                                    Logx.i(T, "onActivityResult for import req=" + req + " res=" + a1);
                                    CurriculumIo.onActivityResult(
                                            (android.app.Activity) self, req, (Integer) a1,
                                            a2 instanceof android.content.Intent
                                                    ? (android.content.Intent) a2 : null);
                                    return null;
                                }
                            }
                            return chain.proceed();
                        });
                hooked++;
                Logx.i(T, "hooked " + k.getSimpleName() + "#onActivityResult(" + n + ")");
            }
        }
        if (hooked == 0) Logx.w(T, "onActivityResult not found on " + cls + " hierarchy");
    }

    /**
     * 课表色板会被宿主重刷：TableView 构造时调 TimeTableDimensionRes#loadRes(context)，
     * 把 TOP_TITLE_BG（顶栏白底）/文字色重新写成默认值 —— 我们不 hook 它的话，
     * 每滑一页白条就回来一次（实测）。
     * 这里在 loadRes 之后把主题色板再盖一遍（见 ThemeBg#applyTokens）。
     */
    private static void hookTableTokens(XposedModule module, ClassLoader host) {
        try {
            Class<?> dim = resolve(host,
                    "com.android.calendar.card.schooltimetable.view.TimeTableDimensionRes");
            if (dim == null) return;
            Method m = null;
            for (Method cand : dim.getDeclaredMethods()) {
                if (cand.getName().equals("loadRes") && cand.getParameterCount() == 1
                        && cand.getParameterTypes()[0] == Context.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                Logx.w(T, "TimeTableDimensionRes#loadRes not found");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setId("table_tokens")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Object a0 = chain.getArg(0);
                        if (a0 instanceof Context) {
                            try {
                                ThemeBg.applyTokens((Context) a0);
                            } catch (Throwable t) {
                                Logx.w(T, "applyTokens after loadRes failed: " + t);
                            }
                        }
                        return r;
                    });
            Logx.i(T, "hooked TimeTableDimensionRes#loadRes (theme tokens)");
        } catch (Throwable t) {
            Logx.w(T, "hookTableTokens failed: " + t);
        }
    }

    /**
     * 课表的「凝光」绘制（见 {@link NingGuang}）。宿主 TableView 是一整块 Canvas 自绘，
     * 课程卡片没有对应的 View，只能在它的绘制时序里插针：
     *
     *   drawCourse$drawCourseItem：白底圆角 → 课程色圆角 →（这里）→ 课程文字
     *   drawEmptyCourse：一行 7 个空格子逐格画 →（这里）→ 下一行
     *
     * 所以卡片挂在 {@code drawCourseText} **之前**（光在文字下面），空格子挂在
     * {@code drawEmptyCourse} **之后**（轮廓光盖在格子上）。
     *
     * 两个方法都不是小函数（130+ 行），JIT 不会内联，hook 能稳定命中 —— 实测见 logs\deploy-*.log 的 hit 行。
     */
    private static void hookTablePaint(XposedModule module, ClassLoader host) {
        final String cls = "com.android.calendar.card.schooltimetable.view.TableView";
        Class<?> tv = resolve(host, cls);
        if (tv == null) return;

        // 1) 课程卡片：drawCourseText(Canvas, String, CourseEntity)
        Method drawText = null;
        for (Method cand : tv.getDeclaredMethods()) {
            if (!"drawCourseText".equals(cand.getName())) continue;
            Class<?>[] pt = cand.getParameterTypes();
            if (pt.length == 3 && pt[0] == android.graphics.Canvas.class
                    && pt[1] == String.class) {
                drawText = cand;
                break;
            }
        }
        if (drawText != null) {
            drawText.setAccessible(true);
            module.hook(drawText)
                    .setId("ng_course_card")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            Object cv = chain.getArg(0);
                            Object course = chain.getArg(2);
                            if (self instanceof android.view.View && cv instanceof android.graphics.Canvas
                                    && course != null) {
                                android.view.View v = (android.view.View) self;
                                NingGuang.card((android.graphics.Canvas) cv, v.getContext(),
                                        f(Ref.callName(course, "getMLeft")),
                                        f(Ref.callName(course, "getMTop")),
                                        f(Ref.callName(course, "getMRight")),
                                        f(Ref.callName(course, "getMBottom")),
                                        f(Ref.field(self, "courseRadius")));
                            }
                        } catch (Throwable t) {
                            Logx.w(T, "凝光卡片绘制失败: " + t);
                        }
                        return chain.proceed();
                    });
            Logx.i(T, "hooked TableView#drawCourseText (凝光课程卡)");
        } else {
            Logx.w(T, "TableView#drawCourseText not found（凝光卡片不可用）");
        }

        // 2) 空格子：drawEmptyCourse(Canvas) —— 一行 7 格，画完再描轮廓光
        Method empty = null;
        for (Method cand : tv.getDeclaredMethods()) {
            if (!"drawEmptyCourse".equals(cand.getName())) continue;
            Class<?>[] pt = cand.getParameterTypes();
            if (pt.length == 1 && pt[0] == android.graphics.Canvas.class) {
                empty = cand;
                break;
            }
        }
        if (empty != null) {
            empty.setAccessible(true);
            module.hook(empty)
                    .setId("ng_empty_cell")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        Object cv = chain.getArg(0);
                        float left = f(Ref.field(self, "cellStartLeft"));
                        float top = f(Ref.field(self, "cellStartTop"));
                        Object r = chain.proceed();
                        try {
                            if (self instanceof android.view.View && cv instanceof android.graphics.Canvas) {
                                NingGuang.emptyRow((android.graphics.Canvas) cv,
                                        ((android.view.View) self).getContext(), self, left, top);
                            }
                        } catch (Throwable t) {
                            Logx.w(T, "凝光空格子绘制失败: " + t);
                        }
                        return r;
                    });
            Logx.i(T, "hooked TableView#drawEmptyCourse (凝光空格子)");
        } else {
            Logx.w(T, "TableView#drawEmptyCourse not found（凝光空格子不可用）");
        }

        hookEmptyCellTint(module, host);
    }

    /**
     * 空格子底色半透明：宿主 {@code drawEmptyCourse} 里取 {@code R.color.empty_course_bg} 走
     * {@code z0.b(int resId, Resources.Theme)}（Kotlin 的默认参数合成方法调它）。
     * 有主题背景时把这一格换成半透明白，背景图/纯色底才透得出来 —— 否则整块网格实心，
     * 「设了背景看不见」「遮罩怎么调都没反应」都是它。
     */
    private static void hookEmptyCellTint(XposedModule module, ClassLoader host) {
        // 取色工具类每个版本换个混淆名：17.3.70 = utils.z0，16.24.2 = utils.v0。
        // 不写死一个，按「类名候选 + 方法签名 (int, Resources$Theme) -> int」找。
        Class<?> util = null;
        Method target = null;
        for (String name : new String[]{
                "com.coloros.calendar.utils.z0",
                "com.coloros.calendar.utils.v0"}) {
            Class<?> k = resolve(host, name);
            if (k == null) continue;
            Method m = colorMethodOf(k);
            if (m != null) {
                util = k;
                target = m;
                break;
            }
        }
        if (target == null) {
            Logx.w(T, "取色工具类没找到（z0/v0 都没有 (int,Theme)->int），空格子保持实心");
            return;
        }
        target.setAccessible(true);
        final String utilName = util.getName();
        final String name = target.getName();
        module.hook(target)
                .setId("empty_cell_tint")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        Object idObj = chain.getArg(0);
                        if (!(idObj instanceof Integer) || !(r instanceof Integer)) return r;
                        int id = (Integer) idObj;
                        // 课程卡的底色/字色：取色这一步换成凝光玻璃
                        if (NingGuang.inTable && appCtx != null && NingGuang.glass(appCtx)) {
                            if (NingGuang.isCardFill(id)) {
                                // 记住宿主这一格的原始课程色：卡片底下 1/3 的白透明渐变要用它
                                NingGuang.lastCourseColor = (Integer) r;
                                return NingGuang.cardFill(appCtx, (Integer) r);
                            }
                            if (NingGuang.isCardInk(id)) {
                                return NingGuang.cardInk(appCtx, (Integer) r);
                            }
                        }
                        // 空格子：id 命中 或 解析出来的颜色命中（R$color 被混淆时靠后者）
                        int emptyId = emptyCellBgId(host);
                        boolean isEmpty = id == emptyId
                                || (Integer) r == emptyCellBgColor(host, id);
                        if (isEmpty) {
                            int tinted = NingGuang.emptyCellTint(appCtx, (Integer) r);
                            if (!emptyTintLogged) {
                                emptyTintLogged = true;
                                Logx.i(T, "空格子半透明生效 color=#" + Integer.toHexString((Integer) r)
                                        + " → #" + Integer.toHexString(tinted)
                                        + " type=" + ThemeStore.type(appCtx));
                            }
                            return tinted;
                        }
                    } catch (Throwable t) {
                        Logx.w(T, "emptyCellTint 失败: " + t);
                    }
                    return r;
                });
        Logx.i(T, "hooked " + utilName + "#" + name + "(int,Theme) (空格子半透明)");
    }

    /** 在 util 类里找「static int (int, Resources.Theme)」那支取色方法 */
    private static Method colorMethodOf(Class<?> k) {
        for (Method m : k.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != int.class) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[0] == int.class
                    && pt[1] == android.content.res.Resources.Theme.class) {
                return m;
            }
        }
        return null;
    }

    private static volatile int emptyCellBgId = -2;
    private static volatile int emptyCellBgValue = -1;
    private static volatile boolean emptyTintLogged;

    /**
     * R.color.empty_course_bg 解析出的实际颜色。
     * 宿主这张表是按 id 取色的，但 R$color 可能被混淆/改包名，id 命不中时用「值」兜底匹配。
     */
    private static int emptyCellBgColor(ClassLoader host, int resId) {
        int cached = emptyCellBgValue;
        if (cached != -1) return cached;
        int value = 0;
        try {
            android.content.res.Resources res = appCtx == null ? null : appCtx.getResources();
            int id = emptyCellBgId(host);
            if (res != null && id != 0) value = res.getColor(id, null);
            if (value == 0) {
                // R$color 被裁掉/改名时的兜底：宿主 resources 里 empty_course_bg 的实测值
                // values=#efeff1（白天，实心）、values-night=#14ffffff（夜里 8% 白）
                boolean night = (res != null && (res.getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                value = night ? 0x14FFFFFF : 0xFFEFEFF1;
            }
        } catch (Throwable t) {
            Logx.w(T, "empty_course_bg 解析失败: " + t);
        }
        emptyCellBgValue = value;
        Logx.i(T, "empty_course_bg color=#" + Integer.toHexString(value));
        return value;
    }

    private static int emptyCellBgId(ClassLoader host) {
        int id = emptyCellBgId;
        if (id != -2) return id;
        try {
            // 首选：按名字问 resources（不依赖 R 类，R8 把 R$color 裁掉也照样能取）
            android.content.res.Resources res = appCtx == null ? null : appCtx.getResources();
            if (res != null) id = res.getIdentifier("empty_course_bg", "color", appCtx.getPackageName());
        } catch (Throwable ignored) {
            id = 0;
        }
        if (id == 0) {
            try {
                Class<?> r = Class.forName("com.android.calendar.R$color", false, host);
                java.lang.reflect.Field f = r.getDeclaredField("empty_course_bg");
                id = f.getInt(null);
            } catch (Throwable t) {
                id = 0;
            }
        }
        emptyCellBgId = id;
        return id;
    }

    private static float f(Object o) {
        return o instanceof Number ? ((Number) o).floatValue() : 0f;
    }

    /** 宿主 Application（z0 是静态工具，拿不到 Context，用这个） */
    static volatile Context appCtx;


    /**
     * 课程卡片玻璃化（凝光的「透」）：宿主画一张卡片是
     *
     * <pre>
     *   whiteBgPaing.setColor(getWHITE_COLOR())          // 实心白底
     *   canvas.drawRoundRect(rect, r, r, whiteBgPaing)
     *   paint.setColor(z0.c(TodoColor.b(color).getBgColor()))  // ← 注意：getBgColor() 是**资源 id**
     *   canvas.drawRoundRect(rect, r, r, paint)
     *   drawCourseText(...)                              // 深色字
     * </pre>
     *
     * 要让底图从卡片里透出来：白底刷透明 + 课程色换成半透明深色 + 字换白。
     *
     * 颜色不能在 {@code TodoColor#getBgColor()} 上改 —— 它返回的是 R.color 的 **id**，
     * 宿主紧接着把 id 交给 {@code z0.c(resId)} 去 getColor，返回颜色会直接
     * {@code Resources$NotFoundException}（实测崩过一次，见 logs\deploy-20261115-134308.log）。
     * 所以改在取色这一步（{@link #hookEmptyCellTint}），按 id 判断。
     */
    private static void hookCourseGlass(XposedModule module, ClassLoader host) {
        Class<?> tv = resolve(host, "com.android.calendar.card.schooltimetable.view.TableView");
        if (tv != null) {
            Method drawCourse = Ref.methodOf(tv, "drawCourse", android.graphics.Canvas.class);
            if (drawCourse != null) {
                drawCourse.setAccessible(true);
                module.hook(drawCourse)
                        .setId("ng_glass_window")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            NingGuang.basePaint = Ref.field(self, "whiteBgPaing");
                            NingGuang.inTable = true;
                            try {
                                if (self instanceof android.view.View && appCtx != null
                                        && ThemeStore.ningguang(appCtx)) {
                                    // 两块材质都要「下面真实画着什么」：先算映射、按需重抓采样位图。
                                    // 映射必须用**当前画布矩阵**（宿主上下滑动是 canvas.translate，
                                    // 拿 View 的屏幕坐标会停在滑动前，玻璃就错位）。
                                    Object a0 = chain.getArg(0);
                                    Backdrop.prepare(appCtx, (android.view.View) self,
                                            a0 instanceof android.graphics.Canvas
                                                    ? (android.graphics.Canvas) a0 : null);
                                }
                                return chain.proceed();
                            } finally {
                                NingGuang.inTable = false;
                                NingGuang.basePaint = null;
                            }
                        });
                Logx.i(T, "hooked TableView#drawCourse (玻璃卡窗口)");
            }
        }

        NingGuang.palette(host);

        // 白底那支 Paint 刷透明：只认 whiteBgPaing 这一个对象，别的 setColor 原样放行
        Method setColor = Ref.methodOf(android.graphics.Paint.class, "setColor", int.class);
        if (setColor != null) {
            setColor.setAccessible(true);
            module.hook(setColor)
                    .setId("ng_card_base_paint")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (NingGuang.inTable && NingGuang.basePaint != null
                                && chain.getThisObject() == NingGuang.basePaint
                                && appCtx != null && NingGuang.darkGlass(appCtx)) {
                            return chain.proceed(new Object[]{0});
                        }
                        return chain.proceed();
                    });
            Logx.i(T, "hooked Paint#setColor (课程卡白底透明)");
        }

        /*
         * 宿主画课程卡的顺序是：
         *   paint.setColor(z0.c(getBgColor()))   ← 我们换成 0（透明）
         *   paint.setAlpha(255)                  ← 又被打回不透明（卡片变纯黑）
         * 所以 setAlpha 也要拦：只对「当前色 == 0 的那支 Paint」在课表窗口内把 alpha 按 0 处理。
         */
        Method setAlpha = Ref.methodOf(android.graphics.Paint.class, "setAlpha", int.class);
        if (setAlpha != null) {
            setAlpha.setAccessible(true);
            module.hook(setAlpha)
                    .setId("ng_card_base_alpha")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (NingGuang.inTable && appCtx != null && NingGuang.glass(appCtx)
                                && chain.getThisObject() instanceof android.graphics.Paint
                                && ((android.graphics.Paint) chain.getThisObject()).getColor() == 0) {
                            return chain.proceed(new Object[]{0});
                        }
                        return chain.proceed();
                    });
            Logx.i(T, "hooked Paint#setAlpha (透明卡片 alpha)");
        }
    }

    private static void hookBlockLegacyTablePage(XposedModule module, ClassLoader host) {
        TableEntryRoute.install(module, host);
    }

    /** 课表管理页实例：切换课表后要把它一起收掉，用户直接回到主页课表页 */
    static volatile java.lang.ref.WeakReference<android.app.Activity> manageRef = null;

    private static void hookManageActivity(XposedModule module, ClassLoader host) {
        Class<?> c = resolve(host, "com.android.calendar.card.schooltimetable.CourseTableManageActivity");
        if (c == null) return;
        Method m = null;
        for (Method cand : c.getDeclaredMethods()) {
            if (cand.getName().equals("onCreate") && cand.getParameterCount() == 1) {
                m = cand;
                break;
            }
        }
        if (m == null) return;
        m.setAccessible(true);
        module.hook(m)
                .setId("manage_activity_track")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof android.app.Activity) {
                        manageRef = new java.lang.ref.WeakReference<>((android.app.Activity) self);
                        Logx.i(T, "课表管理页已记录（切换课表后随老课表页一起收起）");
                    }
                    return r;
                });
        Logx.i(T, "hooked CourseTableManageActivity#onCreate");
    }

    /** 课程提醒页：追加早晨和下午闹钟。 */
    private static void hookReminderPreferences(XposedModule module, ClassLoader host) {
        final String cls = "com.android.calendar.card.schooltimetable.CourseReminderFragment";
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        Method m = null;
        for (Method cand : c.getDeclaredMethods()) {
            if (cand.getName().equals("onCreatePreferences") && cand.getParameterCount() == 2) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "onCreatePreferences not found on " + cls);
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("reminder_inject")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    Logx.i(T, "hit " + cls + "#onCreatePreferences self=" + self);
                    try {
                        if (TableSettingsPage.isPage(self)) TableSettingsPage.replaceAfterCreatePreferences(self, host);
                        else ReminderUiInjector.injectAfterCreatePreferences(self, host);
                    } catch (Throwable t) {
                        Logx.e(T, "inject after proceed failed", t);
                    }
                    return r;
                });
        Logx.i(T, "hooked " + cls + "#onCreatePreferences");
    }

    /** 每晚 21:00 的唤醒：借宿主 AlertReceiver 冷启动进程，本 action 短路 */
    private static void hookNightTick(XposedModule module, ClassLoader host, Context app) {
        final String cls = "com.android.calendar.alerts.AlertReceiver";
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        Method m = null;
        for (Method cand : c.getDeclaredMethods()) {
            if (cand.getName().equals("onReceive") && cand.getParameterCount() == 2) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "onReceive not found on " + cls);
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("night_tick")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a1 = chain.getArg(1);
                    if (a1 instanceof Intent) {
                        Intent it = (Intent) a1;
                        AlarmPeriod followUp = Scheduler.followUpPeriod(it.getAction());
                        if (followUp != null) {
                            NightJob.runAsync(app, "follow-up", followUp);
                            return null;
                        }
                        if (Scheduler.ACTION_TICK.equals(it.getAction())) {
                            final Context ctx = (Context) chain.getArg(0);
                            final Context appCtx = ctx != null && ctx.getApplicationContext() != null
                                    ? ctx.getApplicationContext() : app;
                            Logx.i(T, "HIT nightly tick @ " + Scheduler.fmt(System.currentTimeMillis()));

                            // 调试/自动化入口：--es lspx_import_path <绝对路径> [--ez lspx_import_replace true]
                            String importPath = it.getStringExtra("lspx_import_path");
                            if (importPath != null && !importPath.isEmpty()) {
                                boolean ics = !importPath.toLowerCase().endsWith(".xlsx");
                                boolean replace = it.getBooleanExtra("lspx_import_replace", false);
                                long term = 0L;
                                String termStr = it.getStringExtra("lspx_import_term");   // yyyyMMdd
                                if (termStr != null && termStr.length() == 8) {
                                    try {
                                        java.util.Calendar tc = java.util.Calendar.getInstance();
                                        tc.clear();
                                        tc.set(Integer.parseInt(termStr.substring(0, 4)),
                                                Integer.parseInt(termStr.substring(4, 6)) - 1,
                                                Integer.parseInt(termStr.substring(6, 8)));
                                        term = tc.getTimeInMillis();
                                    } catch (Throwable ignored) {
                                    }
                                }
                                Logx.i(T, "debug import request path=" + importPath
                                        + " ics=" + ics + " replace=" + replace + " term=" + termStr);
                                CurriculumIo.importAndWrite(appCtx, new java.io.File(importPath),
                                        ics, replace, term, it.getBooleanExtra("lspx_import_newtable", false), null);
                                return null;
                            }

                            if (it.getIntExtra("lspx_days", 1) == 0) NightJob.runTodayAsync(appCtx);
                            else if (Prefs.anyAlarmEnabled()) NightJob.runAsync(appCtx, "nightly-21:00");
                            Scheduler.update(appCtx);
                            return null;                        // 短路：不执行宿主逻辑
                        }
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked " + cls + "#onReceive");
    }

    // ── 时钟进程 ──────────────────────────────────────────────────────────────
    private static void installClock(XposedModule module, ClassLoader host) {
        final String cls = "com.oplus.alarmclock.ai.AiSupportContentProvider";
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        Method m = null;
        for (Method cand : c.getDeclaredMethods()) {
            if (cand.getName().equals("addAlarm") && cand.getParameterCount() == 2) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "addAlarm not found on " + cls);
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("clock_add_alarm")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object a0 = chain.getArg(0);
                    Object a1 = chain.getArg(1);
                    if (a0 instanceof Bundle) {
                        Bundle in = (Bundle) a0;
                        String label = in.getString("label");
                        Logx.i(T, "clock hit addAlarm label='" + label + "' hour="
                                + in.getInt("android.intent.extra.alarm.HOUR", -1)
                                + " min=" + in.getInt("android.intent.extra.alarm.MINUTES", -1)
                                + " dow=" + in.getByte("android.intent.extra.alarm.DAYS_OF_WEEK", (byte) -1));
                        if (AlarmPeriod.MORNING.ownsLabel(label) || AlarmPeriod.AFTERNOON.ownsLabel(label)) {
                            // 兜底：无论上层传什么，课程闹钟一律「不重复 + 用完即删 + 启用」
                            in.putByte("android.intent.extra.alarm.DAYS_OF_WEEK", (byte) 0);
                            in.putInt("android.intent.extra.alarm.DELETE_AFTER_USE", -1);
                            in.putInt("android.intent.action.ALARM_ENABLE", 1);
                            Logx.i(T, "clock forced one-shot for '" + label + "'");
                        }
                    }
                    Object r = chain.proceed();
                    if (a1 instanceof Bundle) {
                        Bundle out = (Bundle) a1;
                        Logx.i(T, "clock addAlarm result=" + out.getInt("result", -99)
                                + " ids=" + java.util.Arrays.toString(out.getLongArray("alarm_id_list")));
                    }
                    return r;
                });
        Logx.i(T, "hooked " + cls + "#addAlarm");
    }

    /** 用宿主 ClassLoader 解析类；失败只记日志，不抛 */
    private static Class<?> resolve(ClassLoader host, String name) {
        try {
            return Class.forName(name, false, host);
        } catch (Throwable t) {
            Logx.w(T, "class not found: " + name + " (" + t + ")");
            return null;
        }
    }
}








