package com.lcodexs.coursealarm;

import android.annotation.SuppressLint;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;

/** Runtime contracts verified against calendar 17.3.70, 17.6.20 and 17.8.0. */
// These com.android.calendar classes belong to the host APK, not hidden framework APIs.
@SuppressLint("PrivateApi")
final class HostCompat {
    static final String FOLD = "com.android.calendar.ui.main.calendar.view.DateTabFoldLayout";
    static final String CREATE_HELPER = "com.android.calendar.ui.main.hepler.ImportCourseTableHelper";
    static final String TABLE_ACTIVITY = "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity";
    static final String PAGER = "androidx.viewpager2.widget.ViewPager2";

    private HostCompat() { }

    static Method changeType(ClassLoader loader) throws ReflectiveOperationException {
        // 17.3.70: s(int); 17.6.20/17.8.0: t(int). Other int setters are unrelated.
        return instanceVoid(Class.forName(FOLD, false, loader),
                new String[]{"changeType", "s", "t"}, int.class);
    }

    static Method pagerCurrentItem(Class<?> pager) throws ReflectiveOperationException {
        // m is the public entry; n(int, boolean) is its implementation and bypasses
        // the host's fake-drag guard. Never select by parameter types alone.
        return instanceVoid(pager, new String[]{"setCurrentItem", "m"}, int.class, boolean.class);
    }

    static Method createSheet(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> helper = Class.forName(CREATE_HELPER, false, loader);
        Class<?> activity = Class.forName("androidx.fragment.app.FragmentActivity", false, loader);
        Method result = null;
        for (Method method : helper.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2 || parameters[0] != activity || parameters[1] != boolean.class) continue;
            if (result != null) throw new NoSuchMethodException("创建课表入口不唯一");
            result = method;
        }
        if (result == null) throw new NoSuchMethodException("创建课表入口不可用");
        result.setAccessible(true);
        return result;
    }

    static Method cropImageGetter(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> helper = Class.forName(CREATE_HELPER, false, loader);
        Method getter = helper.getDeclaredMethod(createSheet(loader).getName().equals("G") ? "u" : "z");
        if (Modifier.isStatic(getter.getModifiers()) || getter.getReturnType() != android.net.Uri.class)
            throw new NoSuchMethodException("原生裁剪图片入口不兼容");
        getter.setAccessible(true);
        return getter;
    }

    static android.net.Uri lastCropImage(ClassLoader loader) throws ReflectiveOperationException {
        Method getter = cropImageGetter(loader);
        Class<?> helper = getter.getDeclaringClass();
        for (Field field : helper.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == helper) {
                field.setAccessible(true);
                return (android.net.Uri)getter.invoke(field.get(null));
            }
        }
        throw new NoSuchFieldException("原生课表导入实例不可用");
    }

    static Constructor<?> weekAdapter(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> activity = Class.forName(TABLE_ACTIVITY, false, loader);
        Class<?> base = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, loader);
        try {
            Constructor<?> named = adapterConstructor(activity.getDeclaredField("weekAdapter").getType(), base);
            if (named != null) return named;
        } catch (NoSuchFieldException ignored) { }
        Constructor<?> result = null;
        for (Field field : activity.getDeclaredFields()) {
            Constructor<?> candidate = adapterConstructor(field.getType(), base);
            if (candidate == null || candidate.equals(result)) continue;
            if (result != null) throw new NoSuchMethodException("周课表适配器不唯一");
            result = candidate;
        }
        if (result == null) throw new NoSuchMethodException("周课表适配器不可用");
        return result;
    }

    static Object unitCallback(ClassLoader loader, Consumer<Object> action) throws ReflectiveOperationException {
        Class<?> function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        Object unit = kotlinUnit(loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (!method.getName().equals("invoke") || args == null || args.length != 1)
                throw new UnsupportedOperationException(method.toString());
            action.accept(args[0]);
            return unit;
        });
    }

    static Object kotlinUnit(ClassLoader loader) throws ReflectiveOperationException {
        // All three supplied APKs rename kotlin.Unit to kotlin.s and INSTANCE to a.
        // Look up the singleton by its type so jadx's metadata aliases are irrelevant.
        // Prefer the host's name: the OS itself can also expose kotlin.Unit.
        for (String name : new String[]{"kotlin.s", "kotlin.Unit"}) {
            Class<?> type;
            try { type = Class.forName(name, false, loader); }
            catch (ClassNotFoundException ignored) { continue; }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null && "kotlin.Unit".equals(value.toString())) return value;
                }
            }
        }
        throw new NoSuchFieldException("宿主 Kotlin Unit 单例不可用");
    }

    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "CourseAlarm host callback";
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Constructor<?> adapterConstructor(Class<?> type, Class<?> base) {
        if (type == base || !base.isAssignableFrom(type)) return null;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(Context.class, String.class,
                    List.class, int.class, boolean.class, int.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException ignored) { return null; }
    }

    private static Method instanceVoid(Class<?> owner, String[] names, Class<?>... parameters)
            throws ReflectiveOperationException {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name, parameters);
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + ": " + String.join("/", names));
    }
}
