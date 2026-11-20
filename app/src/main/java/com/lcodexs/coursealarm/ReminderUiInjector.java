package com.lcodexs.coursealarm;

import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 在「课程提醒」设置页（CourseReminderFragment，PreferenceScreen 结构）里追加早晨与下午闹钟设置：
 *   早晨闹钟（开关） / 闹钟时间（提前多少分钟） / 闹钟响铃 / 闹钟振动
 *
 * hook 点：com.android.calendar.card.schooltimetable.CourseReminderFragment
 *          #onCreatePreferences(Bundle, String)   —— 宿主在这里 addPreferencesFromResource
 * 证据：jadx CourseReminderFragment.java:110-164（布局 R.xml.course_reminder_fragment，
 *       键 course_before_reminder / select_before_course_reminder，类 COUISwitchPreference /
 *       COUIMenuPreference / COUIPreferenceCategory）。
 *
 * 两个实测坑（logcat 证据见 logs/deploy-*.log）：
 *  1) Preference 同时有 setTitle(CharSequence) 与 setTitle(int)，按“参数个数”取重载会取到 int 版
 *     → IllegalArgumentException: argument 1 has type int。必须按参数类型挑。
 *  2) 监听器接口被宿主 R8 改名成 androidx.preference.Preference$c / $d
 *     → Class.forName("...$OnPreferenceChangeListener") 必抛。
 *     正确做法：从 setOnPreferenceChangeListener 的形参类型反查接口，再用 Proxy 实现。
 */
final class ReminderUiInjector {
    private static final String T = CourseAlarmEntry.TAG;

    static final String K_SWITCH = "lspx_morning_alarm";
    static final String K_TIME = "lspx_alarm_time";
    static final String K_RING = "lspx_alarm_ring";
    static final String K_VIBRATE = "lspx_alarm_vibrate";

    private ReminderUiInjector() {
    }

    static void injectAfterCreatePreferences(Object fragment, ClassLoader host) {
        if (fragment == null) return;
        try {
            Context ctx0 = (Context) invokeNoArg(fragment, "getContext");
            if (ctx0 == null) ctx0 = (Context) invokeNoArg(fragment, "getActivity");
            if (ctx0 == null) {
                Logx.w(T, "inject: no context on fragment");
                return;
            }
            final Context ctx = ctx0;
            final android.app.Activity activity = (android.app.Activity)
                    invokeNoArg(fragment, "getActivity");

            Object screen = invokeNoArg(fragment, "getPreferenceScreen");
            if (screen == null) {
                Logx.w(T, "inject: getPreferenceScreen() null");
                return;
            }
            HostPicker.probe(activity);
            for (AlarmPeriod period : AlarmPeriod.values()) {
                if (findPreference(screen, K_SWITCH + period.uiSuffix) == null) {
                    addAlarmGroup(screen, host, ctx, activity, period);
                }
                refresh(screen, ctx, period);
            }
        } catch (Throwable t) {
            Logx.e(T, "inject failed", t);
        }
    }

    private static void addAlarmGroup(Object screen, ClassLoader host, Context ctx,
                                      android.app.Activity activity, AlarmPeriod period) {
        Object swEnabled = newPref(host, "com.coui.appcompat.preference.COUISwitchPreference", ctx);
        Object swVibrate = newPref(host, "com.coui.appcompat.preference.COUISwitchPreference", ctx);
        Object menuTime = newPref(host, "com.coui.appcompat.preference.COUIMenuPreference", ctx);
        Object menuRing = newPref(host, "com.coui.appcompat.preference.COUIMenuPreference", ctx);
        if (swEnabled == null || swVibrate == null || menuTime == null || menuRing == null) {
            Logx.w(T, "inject: COUI preference classes missing");
            return;
        }
        Object group = newPref(host, "com.coui.appcompat.preference.COUIPreferenceCategory", ctx);
        if (group != null) {
            setKey(group, "lspx_alarm_group_" + period.name());
            addTo(screen, group);
        } else {
            group = screen;
        }

        String suffix = period.uiSuffix;
        configSwitch(swEnabled, K_SWITCH + suffix, period.title, summary(period));
        configSwitch(swVibrate, K_VIBRATE + suffix, "闹钟振动", "闹钟响铃时同时振动");
        configMenu(menuTime, K_TIME + suffix, "闹钟时间", timeAssignment(ctx, period));
        configMenu(menuRing, K_RING + suffix, "闹钟响铃", ringAssignment(period));
        addTo(group, swEnabled);
        addTo(group, menuTime);
        addTo(group, menuRing);
        addTo(group, swVibrate);
        Runnable refreshSummary = () -> setText(swEnabled, "setSummary", summary(period));

        bindSwitch(swEnabled, (pref, value) -> {
            boolean on = Boolean.TRUE.equals(value);
            Prefs.setEnabled(period, on);
            setPrefEnabled(menuTime, on);
            setPrefEnabled(menuRing, on);
            setPrefEnabled(swVibrate, on);
            Scheduler.update(ctx);
            NightJob.runAsync(ctx, "ui-switch", period, refreshSummary);
            toast(ctx, on ? "正在设置" + period.title + "…" : period.title + "已关闭");
            return true;
        });

        bindClick(menuTime, pref -> {
            HostPicker.Callback picked = minutes -> {
                Prefs.setOffset(period, minutes);
                setAssignment(menuTime, HostPicker.minutesLabel(ctx, minutes));
                NightJob.runAsync(ctx, "ui-time-changed", period, refreshSummary);
            };
            int current = Prefs.offset(period);
            if (!HostPicker.showMinutes(activity, current, picked)) {
                PickerSheet.showMinutes(ctx, current, picked::onPick);
            }
            return true;
        });

        bindClick(menuRing, pref -> {
            HostPicker.RingCallback picked = silent -> {
                Prefs.setSilent(period, silent);
                setAssignment(menuRing, ringAssignment(period));
                NightJob.runAsync(ctx, "ui-ring-changed", period, refreshSummary);
            };
            boolean current = Prefs.silent(period);
            if (!HostPicker.showRing(activity, current, picked)) {
                PickerSheet.showRing(ctx, current, picked::onPick);
            }
            return true;
        });

        bindSwitch(swVibrate, (pref, value) -> {
            Prefs.setVibrate(period, Boolean.TRUE.equals(value));
            NightJob.runAsync(ctx, "ui-vibrate-changed", period, refreshSummary);
            return true;
        });
    }

    private static void refresh(Object screen, Context ctx, AlarmPeriod period) {
        String suffix = period.uiSuffix;
        Object sw = findPreference(screen, K_SWITCH + suffix);
        Object menuTime = findPreference(screen, K_TIME + suffix);
        Object menuRing = findPreference(screen, K_RING + suffix);
        Object swVib = findPreference(screen, K_VIBRATE + suffix);
        boolean on = Prefs.enabled(period);
        if (sw != null) {
            setPrefChecked(sw, on);
            setText(sw, "setSummary", summary(period));
        }
        if (menuTime != null) {
            setAssignment(menuTime, timeAssignment(ctx, period));
            setPrefEnabled(menuTime, on);
        }
        if (menuRing != null) {
            setAssignment(menuRing, ringAssignment(period));
            setPrefEnabled(menuRing, on);
        }
        if (swVib != null) {
            setPrefChecked(swVib, Prefs.vibrate(period));
            setPrefEnabled(swVib, on);
        }
    }

    private static String summary(AlarmPeriod period) {
        String text = "按当前课表" + period.session + "第一节课设置一次性闹钟，每天最多响一次";
        String last = Prefs.lastResult(period);
        return last.isEmpty() ? text : text + " · 上次：" + last;
    }

    private static String timeAssignment(Context ctx, AlarmPeriod period) {
        return HostPicker.minutesLabel(ctx, Prefs.offset(period));
    }

    private static String ringAssignment(AlarmPeriod period) {
        return Prefs.silent(period) ? "静音" : "默认铃声";
    }

    // ── 反射小工具 ────────────────────────────────────────────────────────────

    private static Object newPref(ClassLoader cl, String cls, Context ctx) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            try {
                return c.getConstructor(Context.class).newInstance(ctx);
            } catch (NoSuchMethodException missing) {
                return c.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(ctx, null);
            }
        } catch (Throwable t) {
            Logx.w(T, "new " + cls + " failed: " + t);
            return null;
        }
    }

    private static void configSwitch(Object pref, String key, String title, String summary) {
        setKey(pref, key);
        setPersistentFalse(pref);
        setText(pref, "setTitle", title);
        setText(pref, "setSummary", summary);
    }

    private static void configMenu(Object pref, String key, String title, String assignment) {
        setKey(pref, key);
        setPersistentFalse(pref);
        setText(pref, "setTitle", title);
        setAssignment(pref, assignment);
    }

    private static void setAssignment(Object pref, String text) {
        if (!setText(pref, "setAssignment", text)) {
            setText(pref, "setSummary", text);
        }
    }

    /** 只挑 accept CharSequence 的那个重载（避开 setTitle(int) / setSummary(int)） */
    private static boolean setText(Object pref, String name, String text) {
        Method m = findTextSetter(pref.getClass(), name);
        if (m == null) {
            Logx.w(T, name + " not found on " + pref.getClass().getSimpleName());
            return false;
        }
        try {
            m.setAccessible(true);
            m.invoke(pref, text);
            return true;
        } catch (Throwable t) {
            Logx.w(T, name + " failed: " + t);
            return false;
        }
    }

    private static Method findTextSetter(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p == String.class || p == CharSequence.class
                        || (CharSequence.class.isAssignableFrom(p))) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void setKey(Object pref, String key) {
        Method m = findMethod(pref.getClass(), "setKey", 1, String.class);
        if (m == null) return;
        try {
            m.setAccessible(true);
            m.invoke(pref, key);
        } catch (Throwable t) {
            Logx.w(T, "setKey failed: " + t);
        }
    }

    private static void setPersistentFalse(Object pref) {
        setBool(pref, "setPersistent", false);
    }

    private static void setPrefChecked(Object pref, boolean v) {
        setBool(pref, "setChecked", v);
    }

    private static void setPrefEnabled(Object pref, boolean v) {
        if (pref != null) setBool(pref, "setEnabled", v);
    }

    private static void setBool(Object target, String name, boolean v) {
        Method m = findMethod(target.getClass(), name, 1, boolean.class);
        if (m == null) return;
        try {
            m.setAccessible(true);
            m.invoke(target, v);
        } catch (Throwable t) {
            Logx.w(T, name + " failed: " + t);
        }
    }

    private static void addTo(Object group, Object pref) {
        if (group == null || pref == null) return;
        Method m = findMethod(group.getClass(), "addPreference", 1, null);
        if (m == null) {
            Logx.w(T, "addPreference missing on " + group.getClass());
            return;
        }
        try {
            m.setAccessible(true);
            m.invoke(group, pref);
        } catch (Throwable t) {
            Logx.w(T, "addPreference failed: " + t);
        }
    }

    private static Object findPreference(Object screen, String key) {
        try {
            Method m = findMethod(screen.getClass(), "findPreference", 1, null);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(screen, key);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 监听器：接口名被混淆，从 setter 形参反查 ───────────────────────────────

    interface ChangeHandler {
        boolean onChange(Object pref, Object newValue);
    }

    interface ClickHandler {
        boolean onClick(Object pref);
    }

    private static void bindSwitch(Object pref, ChangeHandler h) {
        Method setter = findMethod(pref.getClass(), "setOnPreferenceChangeListener", 1, null);
        if (setter == null) {
            Logx.w(T, "setOnPreferenceChangeListener missing on " + pref.getClass().getSimpleName());
            return;
        }
        Class<?> iface = setter.getParameterTypes()[0];
        try {
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (p, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) return defaultReturn(m);
                        if (m.getReturnType() == boolean.class) {
                            try {
                                return h.onChange(args != null && args.length > 0 ? args[0] : null,
                                        args != null && args.length > 1 ? args[1] : null);
                            } catch (Throwable t) {
                                Logx.e(T, "change listener failed", t);
                                return false;
                            }
                        }
                        return defaultReturn(m);
                    });
            setter.setAccessible(true);
            setter.invoke(pref, proxy);
            Logx.i(T, "bindSwitch via " + iface.getName());
        } catch (Throwable t) {
            Logx.w(T, "bindSwitch failed: " + t);
        }
    }

    private static void bindClick(Object pref, ClickHandler h) {
        Method setter = findMethod(pref.getClass(), "setOnPreferenceClickListener", 1, null);
        if (setter == null) {
            Logx.w(T, "setOnPreferenceClickListener missing on " + pref.getClass().getSimpleName());
            return;
        }
        Class<?> iface = setter.getParameterTypes()[0];
        try {
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (p, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) return defaultReturn(m);
                        if (m.getReturnType() == boolean.class) {
                            try {
                                return h.onClick(args != null && args.length > 0 ? args[0] : null);
                            } catch (Throwable t) {
                                Logx.e(T, "click listener failed", t);
                                return false;
                            }
                        }
                        return defaultReturn(m);
                    });
            setter.setAccessible(true);
            setter.invoke(pref, proxy);
            Logx.i(T, "bindClick via " + iface.getName());
        } catch (Throwable t) {
            Logx.w(T, "bindClick failed: " + t);
        }
    }

    private static Object defaultReturn(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == void.class) return null;
        return null;
    }

    static Object invokeNoArg(Object target, String name) {
        try {
            Method m = findMethod(target.getClass(), name, 0, null);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按名字 + 参数个数找方法；paramType 非空时要求首参类型匹配（基本类型精确匹配）。
     */
    private static Method findMethod(Class<?> c, String name, int argc, Class<?> paramType) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != argc) continue;
                if (paramType == null) return m;
                Class<?> p = m.getParameterTypes()[0];
                if (paramType.isPrimitive()) {
                    if (p == paramType) return m;
                } else if (paramType.isAssignableFrom(p)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
