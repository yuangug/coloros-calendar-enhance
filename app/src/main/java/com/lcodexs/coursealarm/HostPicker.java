package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 直接复用宿主自己的「提醒时间」滚轮 —— 与页面上原有那行是同一个控件、同一套 COUI 底部弹窗、
 * 同一个「确定」按钮，连滚轮文案都走宿主的 formatter（remind_time_value → "课前 N 分钟"），
 * 所以两个选择的样式完全一致。
 *
 * 真实运行时类名（apktool smali，jadx 的 CommonPickerDialog 是拿 Kotlin @Metadata 还原的名字）：
 *   smali_classes2/com/coloros/calendar/widget/bottomsheetdialog/r.smali
 *     .super  f（=BottomDialogFactory）
 *     P(Context, g, int type, int maxIndex, int index, long)   :183
 *     R()I                                                     ← 选中的 index（jadx: getF21178z）
 *     field x=type  y=maxIndex  A/z=index
 *   g.smali = interface { boolean sureOnClickListener(); }     ← javadoc 名 BottomDialogSureListener
 *   f.m() = androidx.appcompat.app.AlertDialog                  ← 取已显示的对话框（用于改标题）
 *   r$B（companion）.c(index) = String "课前 index*5 分钟"
 *
 * 取不到 / 宿主改版 → 返回 false，调用方回落到自带 sheet，这一行永远不会点不动。
 */
final class HostPicker {
    private static final String T = CourseAlarmEntry.TAG;

    /** 运行时真实类名优先，jadx 还原名作后备 */
    private static final String[] CLS_DIALOG_CANDIDATES = {
            "com.coloros.calendar.widget.bottomsheetdialog.r",
            "com.coloros.calendar.widget.bottomsheetdialog.CommonPickerDialog"
    };
    private static final String[] CLS_LISTENER_CANDIDATES = {
            "com.coloros.calendar.widget.bottomsheetdialog.g",
            "com.coloros.calendar.widget.bottomsheetdialog.BottomDialogSureListener"
    };

    /** 宿主滚轮取值域：index 1..12 → 5..60 分钟（5 分钟一档） */
    static final int STEP = 5;
    static final int MIN_INDEX = 1;
    static final int MAX_INDEX = 12;

    interface Callback {
        void onPick(int minutes);
    }

    private HostPicker() {
    }

    static int indexOf(int minutes) {
        int idx = Math.round(minutes / (float) STEP);
        return Math.max(MIN_INDEX, Math.min(MAX_INDEX, idx));
    }

    static int minutesOf(int index) {
        return Math.max(MIN_INDEX, Math.min(MAX_INDEX, index)) * STEP;
    }

    /** 右值文案与宿主「提醒时间」同源：优先调宿主 formatter，其次宿主字符串资源 */
    static String minutesLabel(Context ctx, int minutes) {
        int index = indexOf(minutes);
        try {
            Class<?> dlg = Class.forName(CLS_DIALOG_CANDIDATES[0], false, ctx.getClassLoader());
            Field b = dlg.getDeclaredField("B");          // companion
            b.setAccessible(true);
            Object companion = b.get(null);
            Method c = companion.getClass().getMethod("c", int.class);
            Object s = c.invoke(companion, index);
            if (s instanceof String && !((String) s).isEmpty()) return (String) s;
        } catch (Throwable ignored) {
        }
        try {
            int id = ctx.getResources().getIdentifier("remind_time_value", "string",
                    ctx.getPackageName());
            if (id != 0) return ctx.getResources().getString(id, minutesOf(index));
        } catch (Throwable t) {
            Logx.w(T, "minutesLabel host res failed: " + t);
        }
        return "课前 " + minutesOf(index) + " 分钟";
    }

    /** 注入时自检一次：把反射契约（类/方法/字段）验一遍并打日志，不弹窗 */
    static void probe(Activity activity) {
        if (activity == null) return;
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> dlgCls = resolve(cl, CLS_DIALOG_CANDIDATES);
            Class<?> listenerCls = resolve(cl, CLS_LISTENER_CANDIDATES);
            boolean ok = dlgCls != null && listenerCls != null
                    && findMethod(dlgCls, "P", 6) != null && readIndexMethod(dlgCls) != null;
            Logx.i(T, "host picker probe: ok=" + ok
                    + " dialog=" + (dlgCls == null ? "?" : dlgCls.getName())
                    + " listener=" + (listenerCls == null ? "?" : listenerCls.getName())
                    + " label='" + minutesLabel(activity, 15) + "'");
        } catch (Throwable t) {
            Logx.w(T, "host picker probe failed (会回落到自带选择器): " + t);
        }
    }

    /**
     * 弹宿主的提醒时间滚轮。
     *
     * @return true = 用的是宿主控件；false = 调用方应回落自带选择器
     */
    static boolean showMinutes(Activity activity, int currentMinutes, Callback cb) {
        if (activity == null) {
            Logx.w(T, "host picker: no activity");
            return false;
        }
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> dlgCls = resolve(cl, CLS_DIALOG_CANDIDATES);
            Class<?> listenerCls = resolve(cl, CLS_LISTENER_CANDIDATES);
            Method p = dlgCls == null ? null : findMethod(dlgCls, "P", 6);
            if (dlgCls == null || listenerCls == null || p == null) {
                Logx.w(T, "host picker unavailable (dialog/listener/P missing)");
                return false;
            }
            final Object dlg = dlgCls.getConstructor().newInstance();

            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{listenerCls},
                    (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return "toString".equals(m.getName()) ? "lspx-sure-listener" : null;
                        }
                        if (m.getReturnType() == boolean.class) {
                            try {
                                int index = readIndex(dlgCls, dlg);
                                int minutes = minutesOf(index);
                                Logx.i(T, "host picker sure: index=" + index + " → " + minutes + " 分钟");
                                cb.onPick(minutes);
                            } catch (Throwable t) {
                                Logx.e(T, "host picker callback failed", t);
                            }
                            return true;
                        }
                        return null;
                    });

            p.setAccessible(true);
            int index = indexOf(currentMinutes);
            // type=5（课程提醒滚轮） / maxIndex=12（5..60 分钟） / index / millis
            p.invoke(dlg, activity, listener, 5, MAX_INDEX, index, 0L);
            Logx.i(T, "host picker shown (type=5 maxIndex=12 index=" + index + ")");
            retitle(dlgCls, dlg, activity, "闹钟时间");
            return true;
        } catch (Throwable t) {
            Logx.w(T, "host picker failed, fallback to own sheet: " + t);
            return false;
        }
    }

    /** 响铃选择的轮子文案：index 1=默认铃声，2=静音 */
    private static final String[] RING_LABELS = {"默认铃声", "静音"};

    /**
     * 响铃也用宿主同一个滚轮弹窗（只是替换滚轮的 formatter，把「课前 N 分钟」换成两项响铃），
     * 所以它和「闹钟时间」在外观/动画/按钮上完全一致。
     *
     * @param silent 当前是否静音
     * @return true = 用的宿主控件；false = 调用方回落自带 sheet
     */
    static boolean showRing(Activity activity, boolean silent, RingCallback cb) {
        if (activity == null) return false;
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> dlgCls = resolve(cl, CLS_DIALOG_CANDIDATES);
            Class<?> listenerCls = resolve(cl, CLS_LISTENER_CANDIDATES);
            Method p = dlgCls == null ? null : findMethod(dlgCls, "P", 6);
            if (dlgCls == null || listenerCls == null || p == null) return false;

            final Object dlg = dlgCls.getConstructor().newInstance();

            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{listenerCls},
                    (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return "toString".equals(m.getName()) ? "lspx-ring-listener" : null;
                        }
                        if (m.getReturnType() == boolean.class) {
                            try {
                                int index = readIndex(dlgCls, dlg);
                                boolean s = index >= 2;
                                Logx.i(T, "host ring picker sure: index=" + index + " silent=" + s);
                                cb.onPick(s);
                            } catch (Throwable t) {
                                Logx.e(T, "host ring callback failed", t);
                            }
                            return true;
                        }
                        return null;
                    });

            p.setAccessible(true);
            int index = silent ? 2 : 1;
            // 借用 type=5 的滚轮外壳，maxIndex=2 让它只有两档
            p.invoke(dlg, activity, listener, 5, RING_LABELS.length, index, 0L);

            boolean swapped = swapFormatter(dlgCls, dlg, cl);
            Logx.i(T, "host ring picker shown (index=" + index + " formatter=" + (swapped ? "自定义" : "宿主") + ")");
            retitle(dlgCls, dlg, activity, "闹钟响铃");
            return true;
        } catch (Throwable t) {
            Logx.w(T, "host ring picker failed, fallback to own sheet: " + t);
            return false;
        }
    }

    interface RingCallback {
        void onPick(boolean silent);
    }

    /** 把 COUINumberPicker 的 formatter 换成我们的两项文案（接口类型从 setFormatter 形参反查） */
    private static boolean swapFormatter(Class<?> dlgCls, Object dlg, ClassLoader cl) {
        try {
            Object picker = null;
            for (Field f : dlgCls.getDeclaredFields()) {
                if (f.getType().getName().endsWith("COUINumberPicker")) {
                    f.setAccessible(true);
                    picker = f.get(dlg);
                    break;
                }
            }
            if (picker == null) {
                Logx.w(T, "ring picker: COUINumberPicker not found");
                return false;
            }
            Method setFormatter = null;
            for (Method m : picker.getClass().getMethods()) {
                if ("setFormatter".equals(m.getName()) && m.getParameterCount() == 1) {
                    setFormatter = m;
                    break;
                }
            }
            if (setFormatter == null) {
                Logx.w(T, "ring picker: setFormatter not found");
                return false;
            }
            final Class<?> fmtIface = setFormatter.getParameterTypes()[0];
            Object formatter = Proxy.newProxyInstance(fmtIface.getClassLoader(),
                    new Class<?>[]{fmtIface}, (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return "toString".equals(m.getName()) ? "lspx-ring-formatter" : null;
                        }
                        if (m.getReturnType() != String.class || args == null || args.length == 0) {
                            return "";
                        }
                        int value = args[0] instanceof Integer ? (Integer) args[0] : 0;
                        int i = value - 1;
                        return i >= 0 && i < RING_LABELS.length ? RING_LABELS[i] : "";
                    });
            setFormatter.setAccessible(true);
            setFormatter.invoke(picker, formatter);
            return true;
        } catch (Throwable t) {
            Logx.w(T, "ring picker: swapFormatter failed: " + t);
            return false;
        }
    }

    /** 宿主标题固定是 R.string.remind_time（提醒时间），换成我们这行的名字 */
    private static void retitle(Class<?> dlgCls, Object dlg, Context ctx, String title) {
        try {
            View root = dialogDecor(dlgCls, dlg);
            if (root == null) return;
            String hostTitle = "";
            try {
                int id = ctx.getResources().getIdentifier("remind_time", "string",
                        ctx.getPackageName());
                if (id != 0) hostTitle = ctx.getResources().getString(id);
            } catch (Throwable ignored) {
            }
            TextView hit = hostTitle.isEmpty() ? null : findText(root, hostTitle);
            if (hit != null) {
                hit.setText(title);
                Logx.i(T, "host picker title → " + title);
            }
        } catch (Throwable t) {
            Logx.w(T, "retitle failed: " + t);
        }
    }

    private static View dialogDecor(Class<?> dlgCls, Object dlg) {
        for (String name : new String[]{"m", "getF21092i"}) {
            Method m = findMethod(dlgCls, name, 0);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                Object d = m.invoke(dlg);
                if (d instanceof android.app.Dialog) {
                    android.view.Window w = ((android.app.Dialog) d).getWindow();
                    return w == null ? null : w.getDecorView();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static TextView findText(View v, String want) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && want.contentEquals(cs)) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findText(g.getChildAt(i), want);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int readIndex(Class<?> dlgCls, Object dlg) throws Exception {
        Method m = readIndexMethod(dlgCls);
        if (m != null) {
            m.setAccessible(true);
            Object v = m.invoke(dlg);
            if (v instanceof Integer) return (Integer) v;
        }
        for (Field f : dlgCls.getDeclaredFields()) {
            if (f.getType() == int.class && ("z".equals(f.getName()) || f.getName().contains("21178"))) {
                f.setAccessible(true);
                return f.getInt(dlg);
            }
        }
        throw new NoSuchMethodException("selected index not readable");
    }

    private static Method readIndexMethod(Class<?> dlgCls) {
        for (String name : new String[]{"R", "getF21178z"}) {
            Method m = findMethod(dlgCls, name, 0);
            if (m != null && m.getReturnType() == int.class) return m;
        }
        return null;
    }

    private static Class<?> resolve(ClassLoader cl, String[] candidates) {
        for (String n : candidates) {
            try {
                return Class.forName(n, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
            }
        }
        return null;
    }
}
