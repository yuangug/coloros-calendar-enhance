package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Own preference content inside the host's native toolbar, navigation and inset container. */
final class TableSettingsPage {
    private static final String ACTIVITY = "com.android.calendar.card.schooltimetable.CourseReminderActivity";
    private static final String FRAGMENT = "com.android.calendar.card.schooltimetable.CourseReminderFragment";
    private static final String PAGE = "com.lcodexs.coursealarm.TABLE_SETTINGS";
    private static final String CALENDAR = "lspx_settings_calendar_id";
    private static final String IDENTITY = "lspx_settings_calendar_identity";
    private static final String SWITCH = "lspx_table_drag_enabled";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "course-settings-page"));
    private static final Map<Object, Binding> BINDINGS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Binding {
        final WeakReference<Activity> activity;
        final WeakReference<Object> preference;
        final long calendarId;
        final String identity;
        boolean saving;
        boolean invalid;

        Binding(Activity activity, Object preference) {
            this.activity = new WeakReference<>(activity);
            this.preference = new WeakReference<>(preference);
            calendarId = activity.getIntent().getLongExtra(CALENDAR, -1);
            identity = activity.getIntent().getStringExtra(IDENTITY);
        }
    }

    private TableSettingsPage() { }

    static void open(Activity activity) {
        long id = TableCtx.hostCurrentTableId(activity);
        WORK.execute(() -> {
            try {
                TableSettings.State state = TableSettings.refresh(activity, id);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        // Do not supply calendar_entity. This page has its own state and must never
                        // initialize the host reminder ViewModel with a real calendar.
                        Intent intent = new Intent().setClassName(activity.getPackageName(), ACTIVITY)
                                .putExtra(PAGE, true).putExtra(CALENDAR, id).putExtra(IDENTITY, state.identity);
                        activity.startActivity(intent);
                    } catch (Exception error) { failed(activity, "无法打开课表设置", error); }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> failed(activity, "无法打开课表设置", error));
            }
        });
    }

    static boolean isPage(Object owner) {
        Activity activity = activity(owner);
        return activity != null && activity.getIntent() != null && activity.getIntent().getBooleanExtra(PAGE, false);
    }

    static void install(XposedModule module, ClassLoader host) {
        Class<?> fragment = Ref.cls(host, FRAGMENT);
        Class<?> activity = Ref.cls(host, ACTIVITY);
        if (fragment == null || activity == null) return;
        Method title = Ref.methodOf(fragment, "getTitle");
        if (title != null) module.hook(title).setId("table_settings_title")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> isPage(chain.getThisObject()) ? "课表设置" : chain.proceed());
        for (String name : new String[]{"persistIfChanged", "onRequestPermissionsResult"}) {
            Method method = Ref.methodByArity(activity, name, name.equals("persistIfChanged") ? 0 : 3);
            if (method != null) module.hook(method).setId("table_settings_isolate_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> isPage(chain.getThisObject()) ? null : chain.proceed());
        }
        Method openReminder = Ref.methodOf(fragment, "openReminder");
        if (openReminder != null) module.hook(openReminder).setId("table_settings_isolate_permission")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> isPage(chain.getThisObject()) ? null : chain.proceed());
        Method preferenceClick = Ref.methodByArity(fragment, "onPreferenceClick", 1);
        if (preferenceClick != null) module.hook(preferenceClick).setId("table_settings_isolate_picker")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> isPage(chain.getThisObject()) ? true : chain.proceed());
        Method resume = Ref.methodOf(fragment, "onResume");
        if (resume != null) module.hook(resume).setId("table_settings_resume")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (isPage(chain.getThisObject())) refresh(BINDINGS.get(chain.getThisObject()));
                    return result;
                });
    }

    /** Called after the native preference XML has supplied its header and category styling. */
    static void replaceAfterCreatePreferences(Object fragment, ClassLoader host) {
        Activity activity = activity(fragment);
        if (activity == null) return;
        try {
            Object screen = invoke(fragment, "getPreferenceScreen");
            if (screen == null) throw new IllegalStateException("原生设置容器不可用");
            Object category = null;
            int count = ((Number) invoke(screen, "getPreferenceCount")).intValue();
            for (int i = 0; i < count; i++) {
                Object item = invoke(screen, "getPreference", i);
                if (item != null && item.getClass().getName().endsWith("PreferenceCategory")) {
                    category = item;
                    break;
                }
            }
            if (category == null) throw new IllegalStateException("原生设置分组不可用");
            clearField(fragment, "courseBeforeRemindPreference");
            clearField(fragment, "selectCourseBeforeRemindPreference");
            invoke(category, "removeAll");
            Object preference = Class.forName("com.coui.appcompat.preference.COUISwitchPreference", false, host)
                    .getConstructor(Context.class).newInstance(activity);
            invoke(preference, "setKey", SWITCH);
            invoke(preference, "setPersistent", false);
            invoke(preference, "setTitle", "是否启用拖动调课");
            invoke(preference, "setSummary", "开启后，长按课程并拖动到目标星期和节次");
            invoke(preference, "setEnabled", false);
            invoke(category, "addPreference", preference);
            Binding binding = new Binding(activity, preference);
            BINDINGS.put(fragment, binding);
            Method setter = Ref.methodByArity(preference.getClass(), "setOnPreferenceChangeListener", 1);
            if (setter == null) throw new NoSuchMethodException("setOnPreferenceChangeListener");
            Class<?> listener = setter.getParameterTypes()[0];
            Object callback = Proxy.newProxyInstance(host, new Class<?>[]{listener}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return HostCompat.objectMethod(proxy, method, args);
                if (method.getReturnType() == boolean.class && args != null && args.length == 2) {
                    save(binding, Boolean.TRUE.equals(args[1]));
                    return false; // The checked value changes only after a successful private write.
                }
                return null;
            });
            setter.setAccessible(true);
            setter.invoke(preference, callback);
            activity.setTitle("课表设置");
            refresh(binding);
        } catch (Exception error) {
            failed(activity, "课表设置不可用", error);
            activity.finish();
        }
    }

    private static void refresh(Binding binding) {
        if (binding == null || binding.saving || binding.invalid) return;
        Activity activity = binding.activity.get();
        if (activity == null) return;
        WORK.execute(() -> {
            try {
                TableSettings.State state = TableSettings.refresh(activity, binding.calendarId);
                if (binding.identity == null || !binding.identity.equals(state.identity))
                    throw new IllegalStateException("课表已变化，请重新打开课表设置");
                activity.runOnUiThread(() -> update(binding, state.enabled, null));
            } catch (Exception error) {
                activity.runOnUiThread(() -> update(binding, false, error));
            }
        });
    }

    private static void save(Binding binding, boolean enabled) {
        Activity activity = binding.activity.get();
        Object preference = binding.preference.get();
        if (activity == null || preference == null || binding.saving || binding.invalid) return;
        binding.saving = true;
        try { invoke(preference, "setEnabled", false); }
        catch (Exception error) { failed(activity, "无法修改课表设置", error); binding.saving = false; return; }
        WORK.execute(() -> {
            try {
                TableSettings.setEnabled(activity, binding.calendarId, binding.identity, enabled);
                activity.runOnUiThread(() -> { binding.saving = false; update(binding, enabled, null); });
            } catch (Exception error) {
                activity.runOnUiThread(() -> { binding.saving = false; update(binding, false, error); });
            }
        });
    }

    private static void update(Binding binding, boolean enabled, Exception error) {
        Activity activity = binding.activity.get();
        Object preference = binding.preference.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || preference == null) return;
        try {
            if (error == null && !binding.saving && !binding.invalid) {
                invoke(preference, "setChecked", enabled);
                invoke(preference, "setEnabled", true);
            } else if (error != null) {
                binding.invalid = true;
                invoke(preference, "setEnabled", false);
                invoke(preference, "setSummary", "课表已变化或不可读取，请返回后重新打开");
                failed(activity, "课表设置保存或读取失败", error);
            }
        } catch (Exception reflectionError) { failed(activity, "课表设置显示失败", reflectionError); }
    }

    private static Activity activity(Object owner) {
        if (owner instanceof Activity) return (Activity) owner;
        Object result = owner == null ? null : Ref.callName(owner, "getActivity");
        return result instanceof Activity ? (Activity) result : null;
    }

    private static void clearField(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, null); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    /** Match parameter types, especially CharSequence versus resource-ID overloads. */
    private static Object invoke(Object owner, String name, Object... args) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                boolean match = true;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    Class<?> parameter = types[i] == boolean.class ? Boolean.class : types[i] == int.class ? Integer.class : types[i];
                    if (args[i] != null && !parameter.isInstance(args[i])) { match = false; break; }
                }
                if (!match) continue;
                method.setAccessible(true);
                return method.invoke(owner, args);
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
    }

    private static void failed(Activity activity, String message, Exception error) {
        Logx.e(CourseAlarmEntry.TAG, message, error);
        if (!activity.isDestroyed()) CurriculumIo.toast(activity, message + "：" + error.getMessage());
    }
}
