package com.lcodexs.coursealarm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 极薄的反射壳：模块工程不引 androidx / 宿主类，一切宿主类型都从宿主 ClassLoader 现取。
 *
 * 只做三件事：拿类、调方法、拿/放字段。所有失败都返回 null 或抛给调用方自己记日志，
 * 这里不吞异常是为了让 `COURSEALARM` 日志能指到具体那一步。
 */
final class Ref {
    private Ref() {
    }

    static Class<?> cls(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> clsStrict(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    /** 按参数类型找 public / 私有构造器，找不到返回 null */
    static Constructor<?> ctorOf(Class<?> c, Class<?>... types) {
        try {
            Constructor<?> k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 扫描所有构造器，找出「参数个数 = n」的那个（宿主适配器类名被混淆，签名仍稳定） */
    static Constructor<?> ctorByArity(Class<?> c, int arity) {
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (k.getParameterCount() == arity) {
                k.setAccessible(true);
                return k;
            }
        }
        return null;
    }

    static Object newWith(Constructor<?> k, Object... args) throws Exception {
        return k.newInstance(args);
    }

    static Method methodOf(Class<?> c, String name, Class<?>... types) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按名字 + 参数个数找方法（同名重载在宿主里罕见，够用） */
    static Method methodByArity(Class<?> c, String name, int arity) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    static Object call(Object target, Method m, Object... args) {
        if (target == null || m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            Logx.w(CourseAlarmEntry.TAG, "ref call " + m.getName() + " failed: " + t);
            return null;
        }
    }

    /** 依次尝试几个零参方法名，返回第一个非 null 结果（宿主 setter/getter 名字被混淆时用） */
    static Object callAny(Object target, String... names) {
        if (target == null) return null;
        for (String n : names) {
            Method m = methodByArity(target.getClass(), n, 0);
            if (m != null) {
                Object v = call(target, m);
                if (v != null) return v;
            }
        }
        return null;
    }

    static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 按形参类型「找」方法（不调用）。宿主把 androidx 的名字一起混淆时（ViewPager2#setCurrentItem → m），
     * 名字查不到、类型查得到；返回的 Method 由调用方自己 invoke，好用返回值判断成败。
     */
    static Method methodByTypes(Class<?> c, Class<?>... types) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!sameTypes(m.getParameterTypes(), types)) continue;
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * 按「参数类型」调用：宿主把 androidx 的方法名也一起混淆了（ViewPager2#setCurrentItem → m），
     * 但形参类型 (int, boolean) 不会变，所以这条路比名字可靠。
     */
    static Object callByTypes(Object target, Class<?>[] types, Object... args) {
        if (target == null) return null;
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!sameTypes(m.getParameterTypes(), types)) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    Logx.w(CourseAlarmEntry.TAG, "callByTypes " + m.getName() + " 失败: " + t);
                }
            }
        }
        Logx.w(CourseAlarmEntry.TAG, "callByTypes 未找到 " + java.util.Arrays.toString(types)
                + " @ " + target.getClass().getName());
        return null;
    }

    private static boolean sameTypes(Class<?>[] a, Class<?>[] b) {        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /**
     * 按「名字 + 参数个数」调用，参数类型不做严格匹配。
     *
     * 为什么需要它：宿主/dex 里同一个类可能由不同 ClassLoader 解析，或者泛型擦除后的形参类型
     * 与我们手上的 Class 对象不是同一个实例时，getDeclaredMethod(name, types) 会直接找不到。
     * 名字 + 个数在本机是唯一的（setAdapter / setCurrentItem / setOrientation …）。
     */
    static Object callName(Object target, String name, Object... args) {
        if (target == null) return null;
        int arity = args == null ? 0 : args.length;
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != arity) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    Logx.w(CourseAlarmEntry.TAG, "callName " + name + " 尝试失败: " + t);
                }
            }
        }
        Logx.w(CourseAlarmEntry.TAG, "callName 未找到 " + name + "/" + arity
                + " @ " + target.getClass().getName());
        return null;
    }
}
