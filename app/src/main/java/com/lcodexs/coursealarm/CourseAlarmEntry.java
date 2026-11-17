package com.lcodexs.coursealarm;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * modern libxposed API 102 入口。框架按 META-INF/xposed/java_init.list 加载本类。
 *
 * 静态作用域同时挂两个包：
 *   com.coloros.calendar     —— 课程数据 + 课程提醒页注入 UI + 21:00 定时 + 建闹钟 + 通知
 *   com.coloros.alarmclock   —— 时钟侧兜底：强制「只响一次」并在日志里留命中证据
 */
public final class CourseAlarmEntry extends XposedModule {
    public static final String TAG = "COURSEALARM";
    public static final String PKG_CALENDAR = "com.coloros.calendar";
    public static final String PKG_CLOCK = "com.coloros.alarmclock";

    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Logx.i(TAG, "module loaded, process=" + safeProcess(param));
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!PKG_CALENDAR.equals(pkg) && !PKG_CLOCK.equals(pkg)) return;
        Logx.i(TAG, "package loaded " + pkg + " first=" + param.isFirstPackage());
        if (!param.isFirstPackage()) return;
        if (!ATTACH_HOOKED.compareAndSet(false, true)) return;

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setId("app_attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            try {
                                onAttached((Context) chain.getThisObject());
                            } catch (Throwable t) {
                                Logx.e(TAG, "onAttached failed", t);
                            }
                        }
                        return result;
                    });
            Logx.i(TAG, "hooked Application.attach for " + pkg);
        } catch (Throwable t) {
            Logx.e(TAG, "hook Application.attach failed", t);
        }
    }

    /** attach 之后：宿主 ClassLoader 就绪，业务 hook 从这里起 */
    private void onAttached(Context context) {
        if (!READY.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final ClassLoader hostLoader = context.getClassLoader();
        final String pkg = app.getPackageName();

        Prefs.init(app);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app, "课程早晨闹钟 注入成功", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
        Logx.i(TAG, "attach pkg=" + pkg + " loader=" + hostLoader);

        try {
            Hooks.install(this, hostLoader, pkg, app);
            Logx.i(TAG, "injection ready pkg=" + pkg);
        } catch (Throwable t) {
            Logx.e(TAG, "install hooks failed", t);
        }
    }

    // ── API 102 热重载 ────────────────────────────────────────────────────────
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        Logx.i(TAG, "hot reloading");
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        try {
            param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        } catch (Throwable t) {
            Logx.e(TAG, "unhook old handles failed", t);
        }
        ATTACH_HOOKED.set(false);
        READY.set(false);
        Logx.i(TAG, "hot reload done, hooks cleared");
    }

    private static String safeProcess(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            return param.getProcessName();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
