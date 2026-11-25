package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String FINISH_WEEK = "lspx_table_finish_week";
    private static final String PERMISSION = "lspx_live_permission";
    private static final String COMPATIBILITY = "lspx_live_compatibility";
    private static final String LIVE_STATUS = "lspx_live_status";
    private static final String TEST_NOTICE = "lspx_live_test_notice";
    private static final String TITLE = "课表设置";
    private static final String SCOPE_NOTE = "需要系统界面作用域，开启后会向 LSPosed 申请系统界面作用域";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "course-settings-page"));
    private static final Map<Object, Binding> BINDINGS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Binding {
        final WeakReference<Activity> activity;
        final Map<String, WeakReference<Object>> preferences = new LinkedHashMap<>();
        final long calendarId;
        final String identity;
        boolean saving;
        boolean invalid;
        boolean awaitingAutoPermission;
        int generation;
        TableSettings.State table;
        boolean finishWeekEnabled;
        CourseLiveSettings.State live;
        Bundle dnd;
        Bundle scope;
        WeakReference<Object> testCategory;

        Binding(Activity activity) {
            this.activity = new WeakReference<>(activity);
            calendarId = activity.getIntent().getLongExtra(CALENDAR, -1);
            identity = activity.getIntent().getStringExtra(IDENTITY);
        }

        Object preference(String key) {
            WeakReference<Object> reference = preferences.get(key);
            return reference == null ? null : reference.get();
        }
    }

    private TableSettingsPage() { }

    static void open(Activity activity) {
        open(activity, false);
    }

    static void open(Activity activity, boolean toggleExperiments) {
        long id = TableCtx.hostCurrentTableId(activity);
        WORK.execute(() -> {
            try {
                TableSettings.State state = TableSettings.refresh(activity, id);
                if (toggleExperiments)
                    CourseLiveSettings.setShowExperiments(activity, !CourseLiveSettings.showExperiments(activity));
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
                .intercept(chain -> isPage(chain.getThisObject()) ? TITLE : chain.proceed());
        Class<?> base = Ref.cls(host, "com.coloros.support.BasePreferenceFragment");
        Method createView = Ref.methodOf(base, "onCreateView", LayoutInflater.class, ViewGroup.class, Bundle.class);
        if (createView != null) module.hook(createView).setId("table_settings_experiment_gesture")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (isPage(chain.getThisObject())) {
                        Object owner = chain.getThisObject();
                        Object toolbar = Ref.field(owner, "mToolbar");
                        if (toolbar instanceof View) ((View) toolbar).post(() -> {
                            Object titleView = Ref.field(toolbar, "mTitleTextView");
                            if (titleView instanceof View) ((View) titleView).setOnLongClickListener(ignored -> {
                                toggleExperiments(BINDINGS.get(owner));
                                return true;
                            });
                        });
                    }
                    return result;
                });
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
            Binding binding = new Binding(activity);
            BINDINGS.put(fragment, binding);
            addSwitch(activity, host, category, binding, SWITCH, "是否启用拖动调课",
                    "开启后，长按课程并拖动到目标星期和节次");
            addSwitch(activity, host, category, binding, FINISH_WEEK, "结束本周课程",
                    "本周没有剩余课程时，可在课表右上角提前切到下一周。本周内再打开课表时会直接显示下一周。");
            Object liveCategory = Class.forName("com.coui.appcompat.preference.COUIPreferenceCategory", false, host)
                    .getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
            invoke(liveCategory, "setKey", "lspx_course_live_category");
            invoke(liveCategory, "setTitle", "课程实时活动");
            invoke(screen, "addPreference", liveCategory);
            addSwitch(activity, host, liveCategory, binding, CourseLiveSettings.RETAIN_ON_CLICK,
                    "点击卡片后保留", "查看课程详情后继续显示，仍可主动关闭本次卡片");
            Object tests = Class.forName("com.coui.appcompat.preference.COUIPreferenceCategory", false, host)
                    .getConstructor(Context.class, AttributeSet.class).newInstance(activity, null);
            invoke(tests, "setKey", "lspx_course_live_experiments");
            invoke(tests, "setTitle", "测试功能");
            invoke(tests, "setVisible", false); // Reveal with the saved values already bound.
            binding.testCategory = new WeakReference<>(tests);
            invoke(screen, "addPreference", tests);
            addSwitch(activity, host, tests, binding, CourseLiveSettings.ENABLED,
                    "启用课程实时活动增强", "增强通知栏中的原生日历实时活动；需要系统界面作用域");
            addSwitch(activity, host, tests, binding, CourseLiveSettings.KEEP_DURING_COURSE,
                    "从课前提醒持续显示到下课", "按「课程提醒」设置的时间持续显示实时活动和流体云；手动关闭后本节不再显示");
            addSwitch(activity, host, tests, binding, CourseLiveSettings.ASTRA_ENABLED,
                    "启用星河岛增强", "独立接管课程胶囊并调用星河岛显示展开卡片");
            CourseAstraStyle[] styles = CourseAstraStyle.values();
            addChoiceMenu(activity, host, tests, binding, CourseLiveSettings.ASTRA_STYLE,
                    "流体云收起样式", "适用于星河岛增强，每张课表独立保存",
                    Arrays.stream(styles).map(style -> style.label).toArray(String[]::new),
                    Arrays.stream(styles).map(style -> style.id).toArray(String[]::new),
                    CourseAstraStyle.TIMETABLE.id);
            addSwitch(activity, host, tests, binding, CourseLiveSettings.SHOW_PROGRESS,
                    "显示课程时长进度", "按上课至下课的完整时间计算，包含课间休息");
            addSwitch(activity, host, tests, binding, CourseLiveSettings.SHOW_DND_BUTTON,
                    "显示免打扰按钮", "在支持的实时活动通知中显示，下课时结束本次免打扰");
            addSwitch(activity, host, tests, binding, CourseLiveSettings.AUTO_DND,
                    "上课时自动开启免打扰", "仅管理本模块开启的免打扰，下课时自动结束");
            addChoiceMenu(activity, host, tests, binding, CourseLiveSettings.RETAIN_MINUTES,
                    "下课后保留时间", "显示“课程已结束”后移除本次卡片",
                    new String[]{"立即移除", "1 分钟", "5 分钟", "10 分钟"},
                    new String[]{"0", "1", "5", "10"}, "5");
            Object liveStatus = newPreference(activity, host, "COUIPreference", binding, LIVE_STATUS,
                    "运行状态", "正在读取状态");
            listener(host, liveStatus, "setOnPreferenceClickListener", args -> {
                if (binding.live != null && binding.live.astraEnabled)
                    CourseLiveCoordinator.connectIsland(activity, binding.identity);
                return true;
            });
            invoke(tests, "addPreference", liveStatus);
            addMenu(activity, host, tests, binding, PERMISSION,
                    "免打扰权限", "正在读取权限状态", () -> openPermission(binding, false));
            addMenu(activity, host, tests, binding, COMPATIBILITY,
                    "系统卡片适配状态", "正在检查当前系统支持情况", () -> showCompatibility(binding));
            Object notice = newPreference(activity, host, "COUIPreference", binding, TEST_NOTICE,
                    null, "测试功能，不保证可用，请谨慎开启");
            invoke(notice, "setSelectable", false);
            invoke(notice, "setEnabled", true);
            invoke(tests, "addPreference", notice);
            activity.setTitle(TITLE);
            refresh(binding);
        } catch (Exception error) {
            failed(activity, "课表设置不可用", error);
            activity.finish();
        }
    }

    private static void toggleExperiments(Binding binding) {
        if (binding == null) return;
        Activity activity = binding.activity.get();
        if (activity == null) return;
        WORK.execute(() -> {
            try {
                boolean visible = !CourseLiveSettings.showExperiments(activity);
                CourseLiveSettings.setShowExperiments(activity, visible);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    Object category = binding.testCategory.get();
                    if (!visible && category != null) {
                        try { invoke(category, "setVisible", false); }
                        catch (Exception error) { failed(activity, "测试配置显示失败", error); }
                    }
                    if (visible) refresh(binding);
                });
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> failed(activity, "无法切换测试配置", error));
            }
        });
    }

    private static void refresh(Binding binding) {
        if (binding == null || binding.saving || binding.invalid) return;
        Activity activity = binding.activity.get();
        if (activity == null) return;
        int generation = ++binding.generation;
        WORK.execute(() -> {
            try {
                TableSettings.State state = TableSettings.refresh(activity, binding.calendarId);
                if (binding.identity == null || !binding.identity.equals(state.identity))
                    throw new IllegalStateException("课表已变化，请重新打开课表设置");
                CourseLiveSettings.State live = CourseLiveSettings.fromTableState(activity, state);
                boolean finishWeekEnabled = TableSettings.finishWeekEnabled(activity, state.identity);
                boolean testVisible = CourseLiveSettings.showExperiments(activity);
                Bundle dnd = testVisible ? CourseDndClient.status(activity) : Bundle.EMPTY;
                Bundle scope = testVisible ? CourseSystemUiScope.status(activity) : Bundle.EMPTY;
                activity.runOnUiThread(() -> {
                    if (!current(binding, generation)) return;
                    binding.table = state;
                    binding.finishWeekEnabled = finishWeekEnabled;
                    binding.live = live;
                    binding.dnd = dnd;
                    binding.scope = scope;
                    update(binding);
                    Object tests = binding.testCategory.get();
                    if (tests != null) try { invoke(tests, "setVisible", testVisible && CourseLiveSettings.showExperiments(activity)); }
                    catch (Exception e) { failed(activity, "测试配置显示失败", e); }
                    if (binding.awaitingAutoPermission) {
                        binding.awaitingAutoPermission = false;
                        if (permissionGranted(dnd)) save(binding, CourseLiveSettings.AUTO_DND, true);
                        else CurriculumIo.toast(activity, "尚未获得所需权限，未开启自动免打扰");
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!current(binding, generation)) return;
                    binding.invalid = true;
                    update(binding);
                    failed(activity, "课表设置读取失败", error);
                });
            }
        });
    }

    private static boolean save(Binding binding, String key, Object value) {
        Activity activity = binding.activity.get();
        if (activity == null || binding.saving || binding.invalid || binding.live == null) return false;
        binding.saving = true;
        int generation = ++binding.generation;
        if (CourseLiveSettings.ENABLED.equals(key) && Boolean.TRUE.equals(value)) {
            ResultReceiver callback = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int resultCode, Bundle result) {
                    if (!current(binding, generation) || !binding.saving) return;
                    if (resultCode == 1 && result != null && result.getBoolean("success")) {
                        persist(binding, activity, key, value, generation);
                    } else scopeFailed(binding, activity, generation, result);
                }
            };
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (current(binding, generation) && binding.saving)
                    scopeFailed(binding, activity, generation, null);
            }, 100000);
            WORK.execute(() -> {
                Bundle accepted = CourseSystemUiScope.request(activity, callback);
                if (!accepted.getBoolean("success")) activity.runOnUiThread(() -> {
                    if (!current(binding, generation)) return;
                    try {
                        // No NEW_TASK: the module verifies the OS-provided caller package.
                        activity.startActivityForResult(CourseSystemUiScope.bootstrap(activity, callback), 0x6CA1);
                    } catch (RuntimeException error) {
                        scopeFailed(binding, activity, generation, accepted);
                    }
                });
            });
            return true;
        }
        persist(binding, activity, key, value, generation);
        return true;
    }

    private static void scopeFailed(Binding binding, Activity activity, int generation, Bundle result) {
        if (!current(binding, generation)) return;
        binding.saving = false;
        CurriculumIo.toast(activity, result == null ? "未获得系统界面作用域"
                : result.getString("message", "未获得系统界面作用域"));
        update(binding);
        refresh(binding);
    }

    private static void persist(Binding binding, Activity activity, String key, Object value, int generation) {
        WORK.execute(() -> {
            try {
                if (CourseLiveSettings.AUTO_DND.equals(key) && Boolean.TRUE.equals(value)) {
                    Bundle permission = CourseDndClient.status(activity);
                    if (!permission.getBoolean("supported"))
                        throw new IllegalStateException(permission.getString("error", "系统暂不支持课程免打扰"));
                    if (!permissionGranted(permission)) {
                        activity.runOnUiThread(() -> {
                            if (!current(binding, generation)) return;
                            binding.saving = false;
                            binding.dnd = permission;
                            update(binding);
                            openPermission(binding, true);
                        });
                        return;
                    }
                }
                if (SWITCH.equals(key))
                    TableSettings.setEnabled(activity, binding.calendarId, binding.identity, Boolean.TRUE.equals(value));
                else if (FINISH_WEEK.equals(key))
                    TableSettings.setFinishWeekEnabled(activity, binding.calendarId, binding.identity, Boolean.TRUE.equals(value));
                else if (CourseLiveSettings.RETAIN_MINUTES.equals(key))
                    CourseLiveSettings.setRetention(activity, binding.calendarId, binding.identity, Integer.parseInt((String) value));
                else if (CourseLiveSettings.ASTRA_STYLE.equals(key))
                    CourseLiveSettings.setAstraStyle(activity, binding.calendarId, binding.identity, CourseAstraStyle.fromId((String) value));
                else CourseLiveSettings.setBoolean(activity, binding.calendarId, binding.identity, key, Boolean.TRUE.equals(value));
                if (!SWITCH.equals(key) && !FINISH_WEEK.equals(key)) CourseLiveCoordinator.settingsChanged();
                activity.runOnUiThread(() -> {
                    if (FINISH_WEEK.equals(key)) HostTablePage.finishWeekSettingChanged(binding.identity);
                    if (!current(binding, generation)) return;
                    binding.saving = false;
                    if (CourseLiveSettings.ENABLED.equals(key) && Boolean.TRUE.equals(value))
                        CurriculumIo.toast(activity, "系统界面作用域已授权；如未生效，请重启系统界面");
                    refresh(binding);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!current(binding, generation)) return;
                    binding.saving = false;
                    update(binding);
                    failed(activity, "课表设置保存失败", error);
                    refresh(binding);
                });
            }
        });
    }

    private static void update(Binding binding) {
        Activity activity = binding.activity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            boolean editable = !binding.invalid && binding.live != null;
            for (Map.Entry<String, WeakReference<Object>> entry : binding.preferences.entrySet()) {
                Object preference = entry.getValue().get();
                if (preference == null) continue;
                String key = entry.getKey();
                if (TEST_NOTICE.equals(key)) continue;
                boolean status = PERMISSION.equals(key) || COMPATIBILITY.equals(key) || LIVE_STATUS.equals(key);
                boolean parent = SWITCH.equals(key) || FINISH_WEEK.equals(key) || CourseLiveSettings.ENABLED.equals(key)
                        || CourseLiveSettings.ASTRA_ENABLED.equals(key) || CourseLiveSettings.RETAIN_ON_CLICK.equals(key)
                        || CourseLiveSettings.ASTRA_STYLE.equals(key) || CourseLiveSettings.KEEP_DURING_COURSE.equals(key);
                boolean granted = binding.scope != null && binding.scope.getBoolean("success");
                boolean anyLive = binding.live != null && (binding.live.astraEnabled || binding.live.enabled && granted);
                boolean dependent = CourseLiveSettings.SHOW_PROGRESS.equals(key)
                        || CourseLiveSettings.SHOW_DND_BUTTON.equals(key)
                        || CourseLiveSettings.AUTO_DND.equals(key)
                        || CourseLiveSettings.RETAIN_MINUTES.equals(key);
                boolean retention = CourseLiveSettings.RETAIN_MINUTES.equals(key) && binding.live != null && binding.live.keepDuringCourse;
                invoke(preference, "setEnabled", status || editable && (parent || dependent && anyLive || retention));
                if (binding.invalid && !status) {
                    invoke(preference, "setSummary", "课表已变化或不可读取，请返回后重新打开");
                } else if (binding.live != null && !status) {
                    if (SWITCH.equals(key)) invoke(preference, "setChecked", binding.table.enabled);
                    else if (FINISH_WEEK.equals(key)) invoke(preference, "setChecked", binding.finishWeekEnabled);
                    else if (CourseLiveSettings.RETAIN_MINUTES.equals(key)) {
                        invoke(preference, "setValue", Integer.toString(binding.live.retainMinutes));
                        textIfChanged(preference, "Assignment", CourseLiveSettings.retentionLabel(binding.live.retainMinutes));
                    } else if (CourseLiveSettings.ASTRA_STYLE.equals(key)) {
                        invoke(preference, "setValue", binding.live.astraStyle.id);
                        textIfChanged(preference, "Assignment", binding.live.astraStyle.label);
                    } else invoke(preference, "setChecked", binding.live.flag(key)
                            && (!CourseLiveSettings.ENABLED.equals(key) || granted));
                }
                if (!binding.invalid && CourseLiveSettings.ENABLED.equals(key))
                    textIfChanged(preference, "Summary", SCOPE_NOTE + (binding.live != null && binding.live.enabled
                            ? granted ? "\n如未生效，请重启系统界面。" : "\n" + (binding.scope == null ? "尚未确认作用域"
                                : binding.scope.getString("message", "系统界面作用域未授权"))
                            : ""));
            }
            Object permission = binding.preference(PERMISSION);
            if (permission != null) {
                textIfChanged(permission, "Assignment", permissionGranted(binding.dnd) ? "已授权" : "未就绪");
                textIfChanged(permission, "Summary", permissionSummary(binding.dnd));
            }
            Object compatibility = binding.preference(COMPATIBILITY);
            if (compatibility != null) textIfChanged(compatibility, "Summary", CourseLiveCapabilities.summary(activity));
            Object liveStatus = binding.preference(LIVE_STATUS);
            if (liveStatus != null) {
                boolean canConnect = editable && binding.live.astraEnabled;
                invoke(liveStatus, "setSelectable", canConnect);
                textIfChanged(liveStatus, "Summary", CourseLiveCapabilities.status(activity, binding.live, binding.scope)
                        + (canConnect ? "\n如系统限制后台启动，可点此连接模块并登记提醒时间。" : ""));
            }
        } catch (Exception reflectionError) { failed(activity, "课表设置显示失败", reflectionError); }
    }

    private static boolean current(Binding binding, int generation) {
        Activity activity = binding.activity.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && binding.generation == generation;
    }

    private static void addSwitch(Activity activity, ClassLoader host, Object category, Binding binding,
                                  String key, String title, String summary) throws Exception {
        Object preference = newPreference(activity, host, "COUISwitchPreference", binding, key, title, summary);
        listener(host, preference, "setOnPreferenceChangeListener", args -> {
            // COUI rolls the switch back immediately when the listener returns false.
            // Let the native animation settle once; only revert if permission or persistence fails.
            return args != null && args.length == 2 && save(binding, key, Boolean.TRUE.equals(args[1]));
        });
        invoke(category, "addPreference", preference);
    }

    private static void addMenu(Activity activity, ClassLoader host, Object category, Binding binding,
                                String key, String title, String summary, Runnable clicked) throws Exception {
        Object preference = newPreference(activity, host, "COUIMenuPreference", binding, key, title, summary);
        listener(host, preference, "setOnPreferenceClickListener", args -> { clicked.run(); return true; });
        invoke(category, "addPreference", preference);
    }

    private static void addChoiceMenu(Activity activity, ClassLoader host, Object category, Binding binding,
                                      String key, String title, String summary, String[] labels,
                                      String[] values, String initialValue) throws Exception {
        Object preference = newPreference(activity, host, "COUIMenuPreference", binding, key, title, summary);
        invoke(preference, "setEntries", (Object) labels);
        invoke(preference, "setEntryValues", (Object) values);
        invoke(preference, "setValue", initialValue);
        invoke(preference, "setAssignment", labels[indexOf(values, initialValue)]);
        listener(host, preference, "setOnPreferenceChangeListener", args -> {
            return args != null && args.length == 2 && args[1] instanceof String selected
                    && Arrays.asList(values).contains(selected) && save(binding, key, selected);
        });
        invoke(category, "addPreference", preference);
    }

    private static int indexOf(String[] values, String selected) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) return i;
        return 0;
    }

    private static Object newPreference(Activity activity, ClassLoader host, String type, Binding binding,
                                         String key, String title, String summary) throws Exception {
        Object preference = Class.forName("com.coui.appcompat.preference." + type, false, host)
                .getConstructor(Context.class).newInstance(activity);
        invoke(preference, "setKey", key);
        invoke(preference, "setPersistent", false);
        invoke(preference, "setTitle", title);
        invoke(preference, "setSummary", summary);
        invoke(preference, "setEnabled", false);
        binding.preferences.put(key, new WeakReference<>(preference));
        return preference;
    }

    private interface PreferenceAction { boolean run(Object[] args); }

    private static void listener(ClassLoader host, Object preference, String name, PreferenceAction action) throws Exception {
        Method setter = Ref.methodByArity(preference.getClass(), name, 1);
        if (setter == null) throw new NoSuchMethodException(name);
        Class<?> listener = setter.getParameterTypes()[0];
        Object callback = Proxy.newProxyInstance(host, new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return HostCompat.objectMethod(proxy, method, args);
            return method.getReturnType() == boolean.class ? action.run(args) : null;
        });
        setter.setAccessible(true);
        setter.invoke(preference, callback);
    }

    private static void openPermission(Binding binding, boolean enableAutoAfterGrant) {
        Activity activity = binding.activity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            binding.awaitingAutoPermission = enableAutoAfterGrant;
            activity.startActivity(CourseDndClient.permissionIntent(activity));
        } catch (Exception error) {
            binding.awaitingAutoPermission = false;
            failed(activity, "无法打开免打扰权限", error);
        }
    }

    private static boolean permissionGranted(Bundle state) {
        return state != null && state.getBoolean("supported") && state.getBoolean("access")
                && state.getBoolean("exactAlarmAccess");
    }

    private static String permissionSummary(Bundle state) {
        if (state == null) return "正在读取权限状态";
        if (!state.getBoolean("supported")) return state.getString("error", "当前系统不支持课程免打扰");
        if (!state.getBoolean("access")) return "未授予本模块免打扰权限，点击前往授权";
        if (!state.getBoolean("exactAlarmAccess")) return "需要授予准时提醒权限，确保下课时结束免打扰";
        if (state.getBoolean("ownedActive")) return "本模块正在开启免打扰，下课时结束";
        if (state.getBoolean("systemActive")) return "系统免打扰已由其他来源开启，本模块不会将其关闭";
        if (!state.getBoolean("ruleEnabled", true)) return "课程免打扰规则已被系统关闭，点击检查权限与规则";
        return "权限已就绪，仅控制本模块创建的免打扰规则";
    }

    private static void showCompatibility(Binding binding) {
        Activity activity = binding.activity.get();
        if (activity == null) return;
        List<String> rows = List.of(CourseLiveCapabilities.summary(activity), "知道了");
        if (!HostSheet.show(activity, "系统卡片适配状态", rows, ignored -> { }))
            CurriculumIo.toast(activity, CourseLiveCapabilities.summary(activity));
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
    private static void textIfChanged(Object item, String property, CharSequence value) throws Exception {
        Object previous = invoke(item, "get" + property);
        if (!android.text.TextUtils.equals(previous instanceof CharSequence ? (CharSequence) previous : null, value))
            invoke(item, "set" + property, value);
    }

    private static Object invoke(Object owner, String name, Object... args) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                boolean match = true;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    Class<?> parameter = types[i] == boolean.class ? Boolean.class : types[i] == int.class ? Integer.class : types[i];
                    if (args[i] == null ? types[i].isPrimitive() : !parameter.isInstance(args[i])) {
                        match = false; break;
                    }
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
