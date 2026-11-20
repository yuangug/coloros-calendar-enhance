package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import org.json.JSONObject;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Narrow host hooks: ordinary agenda/task notifications never enter the enhanced branch. */
final class CourseLiveHooks {
    private static final String ROOT = "com.android.calendar.seedling.notificationfluidcard.";
    private static final Map<Notification, Object> candidates = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Long> retainedClick = new ThreadLocal<>();
    private static final Map<Activity, CourseOccurrence> detailClicks = Collections.synchronizedMap(new WeakHashMap<>());
    private static Object fluidManager;
    private static ClassLoader host;

    static void install(XposedModule module, ClassLoader loader, Context app) {
        if (android.os.Build.VERSION.SDK_INT >= 28 && !app.getPackageName().equals(android.app.Application.getProcessName())) return;
        host = loader; CourseLiveCoordinator.init(app);
        try { installNotification(module, loader); }
        catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live notification hooks", error); }
        try { installClick(module, loader); }
        catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live click hooks", error); }
        try { installFluid(module, loader); }
        catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live fluid hooks", error); }
        try {
            Method receive = Ref.methodOf(Ref.cls(loader, CourseLiveCoordinator.RECEIVER), "onReceive", Context.class, Intent.class);
            module.hook(receive).setId("course_live_private_receiver").intercept(chain -> {
                Intent intent = (Intent) chain.getArg(1);
                if (intent != null && (CourseLiveCoordinator.TICK.equals(intent.getAction())
                        || CourseLiveCoordinator.DISMISS.equals(intent.getAction()))) {
                    CourseLiveCoordinator.receive((BroadcastReceiver) chain.getThisObject(), intent); return null;
                }
                return chain.proceed();
            });
        } catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live receiver hook", error); }
        try { installProvider(module, loader); }
        catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live provider hook", error); }
        try {
            Class<?> alert = Ref.cls(loader, "com.android.calendar.alerts.AlertReceiver");
            module.hook(Ref.methodOf(alert, "onReceive", Context.class, Intent.class)).setId("course_live_revalidation_receiver").intercept(chain -> {
                Intent intent = (Intent) chain.getArg(1);
                if (intent != null && (CourseLiveCoordinator.REVALIDATE.equals(intent.getAction())
                        || CourseLiveCoordinator.DND_ENABLE.equals(intent.getAction()))) {
                    if (CourseLiveCoordinator.acceptsRevalidation(intent))
                        CourseLiveCoordinator.receive((BroadcastReceiver) chain.getThisObject(), intent);
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable error) { Logx.e(CourseAlarmEntry.TAG, "live revalidation hook", error); }
    }

    private static void installNotification(XposedModule module, ClassLoader loader) {
        // The factory name is checked in all supported APKs; match the full shape, not a one-letter name alone.
        for (String name : new String[]{"com.android.calendar.alerts.l", "com.android.calendar.alerts.o"}) {
        Class<?> factory = Ref.cls(loader, name);
        if (factory == null) continue;
        for (Method method : factory.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Notification.class
                    || types.length != 6 || types[0] != Context.class
                    || !types[1].getSimpleName().equals("INotificationInfo") || types[2] != int.class
                    || types[3] != boolean.class || types[4] != String.class || types[5] != boolean.class) continue;
            module.hook(method).setId("course_live_notification_factory").intercept(chain -> {
                Notification notification = (Notification) chain.proceed(); Object info = chain.getArg(1);
                if (notification != null && Boolean.TRUE.equals(Ref.callName(info, "isCourse"))) {
                    notification.extras.putString("lspx.course.publication", java.util.UUID.randomUUID().toString());
                    notification.extras.putBoolean("lspx.course.local", (Boolean) chain.getArg(5));
                    notification.extras.putLong("lspx.course.alarm", CourseLiveCoordinator.number(info, "getAlarmTime"));
                    candidates.put(notification, info);
                    CourseLiveCoordinator.retainImmediately(info, notification);
                }
                return notification;
            });
            CourseLiveCapabilities.notificationHook = true;
        }
        }
        Method notify = Ref.methodOf(NotificationManager.class, "notify", String.class, int.class, Notification.class);
        module.hook(notify).setId("course_live_after_native_publish").intercept(chain -> {
            Notification notification = (Notification) chain.getArg(2);
            Object info = candidates.remove(notification);
            CourseOccurrence item = info == null ? null : CourseLiveCoordinator.find(
                    CourseLiveCoordinator.number(info, "getTargetId"), CourseLiveCoordinator.number(info, "getBeginTime"));
            if (CourseLiveCoordinator.suppressed(item)) return null;
            if (item != null && "tag_normal_remind".equals(chain.getArg(0))
                    && (Integer) chain.getArg(1) == (int) item.eventId
                    && CourseLiveSeedlingState.hasNotification(item)) {
                CourseLiveCoordinator.rememberClick(item, CourseLiveCoordinator.number(info, "getAlarmTime"));
                return null;
            }
            Object result = chain.proceed();
            if (info != null) CourseLiveCoordinator.notification(info, notification);
            return result;
        });
    }

    private static void installClick(XposedModule module, ClassLoader loader) {
        Class<?> activity = Ref.cls(loader, "com.android.calendar.oppo.alerts.OppoAlertActivity");
        module.hook(Ref.methodOf(activity, "onCreate", Bundle.class)).setId("course_live_detail_entry").intercept(chain -> {
            Intent intent = ((Activity) chain.getThisObject()).getIntent();
            Long previous = retainedClick.get();
            long event = -1, alarm = -1;
            if (intent != null) {
                if ("android.intent.action.card.NOTIFICATION_FLUID_CARD".equals(intent.getAction())) {
                    event = parse(intent.getStringExtra("event_id")); alarm = parse(intent.getStringExtra("alarm_time"));
                } else { event = intent.getLongExtra("target_id", -1); alarm = intent.getLongExtra("alarm_time", -1); }
            }
            CourseLiveCoordinator.Click click = CourseLiveCoordinator.handleClick(intent, event, alarm);
            if (click != null) {
                if (click.retain()) retainedClick.set(event);
                if (click.occurrence() != null) detailClicks.put((Activity) chain.getThisObject(), click.occurrence());
            }
            try { return chain.proceed(); }
            finally { if (previous == null) retainedClick.remove(); else retainedClick.set(previous); }
        });
        // Native alert queries can return no alert row after the original reminder was consumed.
        // A receipt names the exact course row independently of that transient alert table.
        Method completion = null;
        for (Method candidate : activity.getDeclaredMethods()) {
            if (candidate.getReturnType() != void.class
                    || !java.util.Arrays.equals(candidate.getParameterTypes(), new Class<?>[]{android.database.Cursor.class})) continue;
            if (completion != null) { completion = null; break; }
            completion = candidate;
        }
        if (completion != null) module.hook(completion).setId("course_live_detail_from_receipt").intercept(chain -> {
            Activity alert = (Activity) chain.getThisObject();
            CourseOccurrence item = detailClicks.remove(alert);
            if (item == null || !CourseLiveDetail.open(alert, item)) return chain.proceed();
            android.database.Cursor cursor = (android.database.Cursor) chain.getArg(0);
            if (cursor != null) cursor.close();
            alert.finish();
            return null;
        });
        module.hook(Ref.methodOf(NotificationManager.class, "cancel", String.class, int.class))
                .setId("course_live_click_cancel_only").intercept(chain -> {
                    Long event = retainedClick.get();
                    if (event != null && "tag_normal_remind".equals(chain.getArg(0)) && event.intValue() == (Integer) chain.getArg(1)) return null;
                    return chain.proceed();
                });
    }

    private static void installFluid(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> managerClass = Class.forName(ROOT + "FluidSeedingManager", false, loader);
        // Do not initialize this singleton during Application.attach: it resolves the host Application.
        for (Method method : managerClass.getDeclaredMethods()) {
            if (method.getName().equals("closeFluidSeedingCard") && method.getParameterCount() == 1) {
                module.hook(method).setId("course_live_click_fluid_" + method.getParameterTypes()[0].getSimpleName()).intercept(chain -> {
                    fluidManager = chain.getThisObject();
                    Long event = retainedClick.get(); Object argument = chain.getArg(0);
                    if (event != null && ((argument instanceof Number && ((Number) argument).longValue() == event)
                            || (argument instanceof String && (modelEvent(model((String) argument)) == event
                                || CourseLiveSeedlingState.event((String) argument) == event)))) return null;
                    return chain.proceed();
                });
            }
        }
        module.hook(Ref.methodOf(managerClass, "updateFluidSeedingCards")).setId("course_live_fluid_deadline").intercept(chain -> {
            fluidManager = chain.getThisObject();
            boolean any = false;
            for (String service : cards().keySet()) if (occurrence(model(service)) != null
                    || CourseLiveSeedlingState.event(service) > 0) { any = true; break; }
            if (!any) return chain.proceed();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Object> entry : cards().entrySet()) {
                Object model = model(entry.getKey());
                if (model == null || occurrence(model) != null || CourseLiveSeedlingState.event(entry.getKey()) > 0) continue;
                long start = CourseLiveCoordinator.number(model, "getBeginTime"), end = CourseLiveCoordinator.number(model, "getEndTime");
                long deadline = end >= start ? Math.min(start + 600000L, end) : start + 600000L;
                if (now >= deadline || now <= CourseLiveCoordinator.number(model, "getAlarmTime")) Ref.callName(entry.getValue(), "closeFluidCard");
                else Ref.callName(entry.getValue(), "updateFluidCard", (Object) null);
            }
            CourseLiveCoordinator.requestTick(); return null;
        });
        module.hook(Ref.methodOf(managerClass, "handleMethodCalls", String.class, Bundle.class))
                .setId("course_live_manual_fluid_close").intercept(chain -> {
                    String method = (String) chain.getArg(0); Bundle extras = (Bundle) chain.getArg(1);
                    if (extras != null && "closeReminder".equals(method)) {
                        String service = extras.getString("serviceInstanceId");
                        CourseLiveCoordinator.dismiss(occurrence(model(service)));
                        CourseLiveSeedlingState.manualClose(service);
                    }
                    return chain.proceed();
                });
        Method unsubscribed = Ref.methodByArity(managerClass, "onUnSubscribed", 2);
        module.hook(unsubscribed).setId("course_live_manual_unsubscribe").intercept(chain -> {
            String service = text(Ref.callName(chain.getArg(1), "getServiceInstanceId"));
            Object proxy = cards().get(service); Object delegate = Ref.field(proxy, "$$delegate_0");
            CourseOccurrence item = occurrence(model(service));
            boolean closed = Boolean.TRUE.equals(Ref.callName(delegate, "isClosedReminder"))
                    || CourseLiveSeedlingState.isClosing(service);
            Object result = chain.proceed();
            CourseLiveSeedlingState.unsubscribed(chain.getArg(1), closed);
            if (item != null && !closed && Boolean.TRUE.equals(Ref.callName(proxy, "checkAllUnSubscribed")))
                CourseLiveCoordinator.dismiss(item);
            return result;
        });
        module.hook(Ref.methodByArity(managerClass, "onSubscribed", 2)).setId("course_live_native_identity_receipt").intercept(chain -> {
            fluidManager = chain.getThisObject();
            Object result = chain.proceed();
            Object card = chain.getArg(1); String service = text(Ref.callName(card, "getServiceInstanceId"));
            CourseLiveSeedlingState.subscribed(model(service), card);
            return result;
        });
        Method send = Ref.methodByArity(managerClass, "sendFluidSeedingCard", 1);
        module.hook(send).setId("course_live_fluid_instance_identity").intercept(chain -> {
            Object info = chain.getArg(0);
            if (!Boolean.TRUE.equals(Ref.callName(info, "isCourse"))) return chain.proceed();
            CourseOccurrence item = CourseLiveCoordinator.find(CourseLiveCoordinator.number(info, "getTargetId"),
                    CourseLiveCoordinator.number(info, "getBeginTime"));
            if (item != null) {
                if (CourseLiveCoordinator.suppressed(item)) return null;
                for (Map.Entry<String, Object> entry : cards().entrySet()) {
                    if (CourseLiveSeedlingState.isClosing(entry.getKey())) continue;
                    CourseOccurrence existing = occurrence(model(entry.getKey()));
                    if (existing != null && existing.key.equals(item.key)) {
                        Ref.callName(entry.getValue(), "updateFluidCard", (Object) null); return null;
                    }
                }
            }
            return chain.proceed();
        });
        Class<?> modelClass = Class.forName(ROOT + "entity.FluidSeedingModel", false, loader);
        module.hook(Ref.methodOf(modelClass, "getTimingDesc")).setId("course_live_native_status").intercept(chain -> {
            CourseOccurrence item = occurrence(chain.getThisObject());
            CourseLiveSettings.State state = CourseLiveCoordinator.settings(item);
            if (state == null) return chain.proceed();
            long now = System.currentTimeMillis();
            String compact = now >= item.end ? "已下课" : now >= item.start ? "上课中" : "即将上课";
            return Class.forName("kotlin.Pair", false, loader).getConstructor(Object.class, Object.class)
                    .newInstance(compact, item.timing(now));
        });
        Class<?> helper = Ref.cls(loader, ROOT + "NotificationFluidSeedingHelper");
        module.hook(Ref.methodByArity(helper, "buildCardJSON", 3)).setId("course_live_seedling_protocol_v1").intercept(chain -> {
            Object result = chain.proceed(); CourseOccurrence item = occurrence(chain.getArg(2));
            CourseLiveSettings.State state = CourseLiveCoordinator.settings(item);
            if (state != null && result != null) {
                JSONObject first = (JSONObject) Ref.callName(result, "getFirst");
                JSONObject second = (JSONObject) Ref.callName(result, "getSecond");
                JSONObject initial = first == null ? null : first.optJSONObject("initData");
                for (JSONObject data : new JSONObject[]{initial, second}) if (data != null) {
                    // Versioned metadata is intentionally not advertised as a rendered progress widget.
                    data.put("courseActivityVersion", 1).put("courseStartTime", item.start).put("courseEndTime", item.end)
                            .put("courseActivityDeadline", item.expiresAt(state.retainMinutes))
                            .put("courseProgressEnabled", state.enabled && state.showProgress);
                    if (state.enabled && state.showProgress) data.put("courseProgress", item.progress(System.currentTimeMillis()));
                }
            }
            return result;
        });
        CourseLiveCapabilities.fluidHook = true;
    }

    private static void installProvider(XposedModule module, ClassLoader loader) {
        Class<?> type = Ref.cls(loader, "com.android.calendar.card.CourseContentProvider");
        if (type == null) return;
        Method call = Ref.methodOf(type, "call", String.class, String.class, Bundle.class);
        if (call == null) return;
        module.hook(call).setId("course_live_provider_readonly_v1").intercept(chain -> {
            if (!type.isInstance(chain.getThisObject())) return chain.proceed();
            String method = (String) chain.getArg(0);
            if (CourseNativeCardBridge.QUERY.equals(method) || CourseNativeCardBridge.CAPABILITY.equals(method))
                return CourseNativeCardBridge.call(((android.content.ContentProvider) chain.getThisObject()).getContext(),
                        method, (Bundle) chain.getArg(2));
            if (!"lspx.course.activity.v1".equals(method)) return chain.proceed();
            // Internal status is not an unauthenticated event/setting enumeration API.
            if (Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException("Calendar UID required");
            Bundle query = (Bundle) chain.getArg(2);
            return CourseLiveCoordinator.stateBundle(query == null ? -1 : query.getLong("eventId", -1),
                    query == null ? -1 : query.getLong("start", -1));
        });
    }

    static void refreshSeedling() {
        CourseLiveSeedlingState.refresh();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object> entry : cards().entrySet()) {
            if (CourseLiveSeedlingState.isClosing(entry.getKey())) continue;
            CourseOccurrence item = occurrence(model(entry.getKey()));
            CourseLiveSettings.State state = CourseLiveCoordinator.settings(item);
            if (state == null) continue;
            if (CourseLiveSeedlingState.has(item)) continue;
            if (CourseLiveCoordinator.isDismissed(item) || now >= item.expiresAt(state.retainMinutes)) Ref.callName(entry.getValue(), "closeFluidCard");
            else Ref.callName(entry.getValue(), "updateFluidCard", (Object) null);
        }
    }

    static void adoptCards() {
        for (Map.Entry<String, Object> entry : cards().entrySet()) {
            Object delegate = Ref.field(entry.getValue(), "$$delegate_0");
            Object card = Ref.callName(delegate, "getNowCard");
            CourseLiveSeedlingState.subscribed(model(entry.getKey()), card);
        }
    }

    static void closeOccurrence(CourseOccurrence item) {
        CourseLiveSeedlingState.close(item);
        for (Map.Entry<String, Object> entry : cards().entrySet()) {
            CourseOccurrence candidate = occurrence(model(entry.getKey()));
            if (candidate != null && candidate.key.equals(item.key)) Ref.callName(entry.getValue(), "closeFluidCard");
        }
    }

    static boolean hasCard(CourseOccurrence item) {
        if (CourseLiveSeedlingState.has(item)) return true;
        for (String service : cards().keySet()) {
            CourseOccurrence candidate = occurrence(model(service));
            if (candidate != null && candidate.key.equals(item.key)) return true;
        }
        return false;
    }

    static CourseOccurrence occurrence(Object model) {
        return model == null || CourseLiveCoordinator.number(model, "getExtendedProperties") != CourseWriter.COURSE_EXT_PROP
                ? null : CourseLiveCoordinator.find(modelEvent(model), CourseLiveCoordinator.number(model, "getBeginTime"));
    }
    private static long modelEvent(Object model) { return CourseLiveCoordinator.number(model, "getEventId"); }
    private static Object manager() {
        if (fluidManager == null && host != null) try {
            fluidManager = Class.forName(ROOT + "FluidSeedingManager", false, host).getField("INSTANCE").get(null);
        } catch (Exception error) { Logx.w(CourseAlarmEntry.TAG, "live native manager unavailable: " + error); }
        return fluidManager;
    }
    private static Object model(String service) { return Ref.callName(manager(), "getFluidSeedingModel", service); }
    @SuppressWarnings("unchecked") private static Map<String, Object> cards() {
        Object value = Ref.callName(manager(), "getFluidSeedingCards");
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }
    private static long parse(String text) { try { return Long.parseLong(text); } catch (RuntimeException ignored) { return -1; } }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
