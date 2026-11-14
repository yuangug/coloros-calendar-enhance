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
        hookReminderPreferences(module, host);
        hookNightTick(module, host, app);
        hookCourseTableMenu(module, host);
        hookActivityResult(module, host);
        hookTableTheme(module, host);
        hookTableCanvas(module, host);
        MainTabHook.install(module, host);

        // 让「每晚 21:00」这个定时器始终存在（App 每次起进程都补一次，幂等）
        try {
            Scheduler.ensureScheduled(app);
            Logx.i(T, "nightly tick ensured, next=" + Scheduler.fmt(Scheduler.nextTickMillis())
                    + " enabled=" + Prefs.enabled());
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
        if (menu.findItem(ID_HOST_SCHOOL_TABLE) != null) {
            menu.removeItem(ID_HOST_SCHOOL_TABLE);
            Logx.i(T, "menu: hid host 课程表 item");
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
            ThemeBg.showThemeMenu(act);
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

    /** 课表页主题：每次进入/回到页面把背景图铺上（幂等） */
    private static void hookTableTheme(XposedModule module, ClassLoader host) {
        final String cls = "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity";
        Class<?> c = resolve(host, cls);
        if (c == null) return;
        int hooks = 0;
        for (Method cand : c.getDeclaredMethods()) {
            String nm = cand.getName();
            boolean isCreate = nm.equals("onCreate") && cand.getParameterCount() == 1;
            boolean isResume = nm.equals("onResume") && cand.getParameterCount() == 0;
            if (!isCreate && !isResume) continue;
            cand.setAccessible(true);
            module.hook(cand)
                    .setId("theme_" + nm)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof android.app.Activity) {
                            try {
                                ThemeBg.apply((android.app.Activity) self);
                            } catch (Throwable t) {
                                Logx.e(T, "theme apply failed", t);
                            }
                        }
                        return r;
                    });
            hooks++;
        }
        Logx.i(T, "hooked table theme on " + cls + " (" + hooks + " entry points)");
    }
    /**
     * 课表整张是 TableView 自绘（canvas）。在它 onDraw 之前把主题背景铺上去，
     * 这样背景就在格子和课程卡片之下、又不会被不透明容器盖掉。
     */
    private static void hookTableCanvas(XposedModule module, ClassLoader host) {
        Class<?> tv = resolve(host, "com.android.calendar.card.schooltimetable.view.TableView");
        if (tv == null) return;
        Method m = null;
        for (Method cand : tv.getDeclaredMethods()) {
            if (cand.getName().equals("onDraw") && cand.getParameterCount() == 1
                    && cand.getParameterTypes()[0] == android.graphics.Canvas.class) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            Logx.w(T, "TableView#onDraw(Canvas) not found");
            return;
        }
        m.setAccessible(true);
        module.hook(m)
                .setId("table_canvas_bg")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object self = chain.getThisObject();
                    Object a0 = chain.getArg(0);
                    if (self instanceof android.view.View && a0 instanceof android.graphics.Canvas) {
                        android.view.View v = (android.view.View) self;
                        try {
                            ThemeBg.drawBgOnCanvas((android.graphics.Canvas) a0, v.getWidth(), v.getHeight());
                        } catch (Throwable t) {
                            Logx.w(T, "canvas bg failed: " + t);
                        }
                    }
                    return chain.proceed();
                });
        Logx.i(T, "hooked TableView#onDraw for theme background");
    }
    /** 课程提醒页：追加「早晨闹钟」那张卡片 */
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
                        ReminderUiInjector.injectAfterCreatePreferences(self, host);
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

                            int days = it.getIntExtra("lspx_days", 1);
                            if (days < 0 || days > 1) days = 1;
                            NightJob.runAsync(appCtx, "nightly-21:00", days);
                            Scheduler.ensureScheduled(appCtx);   // 排明天的 21:00
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
                        if (label != null && label.startsWith(AlarmBridge.LABEL_PREFIX)) {
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
