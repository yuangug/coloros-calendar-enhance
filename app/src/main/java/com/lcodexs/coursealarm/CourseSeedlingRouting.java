package com.lcodexs.coursealarm;

import android.content.Context;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Routes the same authenticated course Seedling; the ROM decides which hosts can subscribe. */
final class CourseSeedlingRouting {
    private static final String SEEDLING = "com.oplus.pantanal.seedling.";
    static volatile boolean available;
    private static final java.util.Set<String> hidden = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> tool = Class.forName(SEEDLING + "util.SeedlingTool", false, loader);
        Class<?> intent = Class.forName(SEEDLING + "bean.SeedlingIntent", false, loader);
        Class<?> card = Class.forName(SEEDLING + "bean.SeedlingCard", false, loader);
        Class<?> host = Class.forName(SEEDLING + "bean.SeedlingHostEnum", false, loader);
        Class<?> options = Class.forName(SEEDLING + "update.SeedlingCardOptions", false, loader);
        Object statusBar = host.getField("StatusBar").get(null);
        Object notification = host.getField("Notification").get(null);
        Method getMap = options.getMethod("getShowHostMap");
        Method setMap = options.getMethod("setShowHostMap", Map.class);
        Method setPanel = options.getMethod("setRequestShowPanel", Boolean.class);
        Method setHide = options.getMethod("setRequestHideStatusBar", boolean.class);
        Method send = tool.getMethod("sendSeedling", Context.class, intent,
                Class.forName(SEEDLING + "intent.IIntentResultCallBack", false, loader));
        Method update = tool.getMethod("updateAllCardData", card, String.class, JSONObject.class, options, int.class,
                Class.forName(SEEDLING + "update.INegativeFeedbackCallback", false, loader));
        Method getFlag = intent.getMethod("getFlag"), getOptions = intent.getMethod("getCardOptions"),
                getData = intent.getMethod("getData"), getId = intent.getMethod("getServiceInstanceId");
        Object aod = null;
        try { aod = host.getField("AOD").get(null); } catch (NoSuchFieldException ignored) { }
        Route route = new Route(statusBar, notification, aod, getMap, setMap, setPanel, setHide);
        module.hook(send).setId("course_astra_seedling_start").intercept(chain -> {
            Object request = chain.getArg(1);
            if (request != null && getFlag.invoke(request) instanceof Enum<?> flag) {
                String service = (String) getId.invoke(request);
                if ("START".equals(flag.name()))
                    route.apply((JSONObject) getData.invoke(request), service, getOptions.invoke(request), true);
                else if ("END".equals(flag.name()) && service != null) hidden.remove(service);
            }
            return chain.proceed();
        });
        module.hook(update).setId("course_astra_seedling_update").intercept(chain -> {
            route.apply((JSONObject) chain.getArg(2), (String) chain.getArg(1), chain.getArg(3), false);
            return chain.proceed();
        });
        available = true;
    }

    private record Route(Object statusBar, Object notification, Object aod, Method getMap, Method setMap,
                         Method setPanel, Method setHide) {
        void apply(JSONObject data, String service, Object options, boolean startRequest) {
            if (!available || data == null || options == null || service == null || service.isEmpty()
                    || !service.equals(data.optString("serviceInstanceId"))
                    || !(data.optBoolean("courseAod") || data.optBoolean("courseCapsuleRouting") || data.optBoolean("courseKeepVisible") || hidden.contains(service))) return;
            long event = data.optLong("eventId", -1), start = data.optLong("courseStartTime", -1);
            if (start <= 0 || event <= 0) return;
            CourseOccurrence item = CourseLiveCoordinator.find(event, start);
            if (item == null) return;
            boolean astra = data.optBoolean("courseCapsuleRouting") && CourseLiveCoordinator.astraOwnsCapsule(item);
            boolean keep = data.optBoolean("courseKeepVisible") && CourseLiveCoordinator.keepVisible(item, System.currentTimeMillis());
            boolean changeCapsule = astra || keep || hidden.contains(service);
            boolean requestAod = aod != null && data.optBoolean("courseAod");
            if (!requestAod && !changeCapsule) return;
            try {
                Map<Object, Object> hosts = new LinkedHashMap<>();
                if (getMap.invoke(options) instanceof Map<?, ?> old) hosts.putAll(old);
                if (requestAod) hosts.putIfAbsent(aod, true);
                if (!changeCapsule) { setMap.invoke(options, hosts); return; }
                hosts.put(statusBar, !astra);
                hosts.put(notification, true);
                setMap.invoke(options, hosts);
                if (astra) setPanel.invoke(options, Boolean.FALSE);
                else if (!startRequest) setPanel.invoke(options, (Object) null); // Keep the capsule, don't re-open its panel.
                setHide.invoke(options, astra);
                boolean changed = astra ? hidden.add(service) : hidden.remove(service);
                if (startRequest || changed) Logx.i(CourseAlarmEntry.TAG, "course Seedling route "
                        + (startRequest ? "START" : "UPDATE") + " StatusBar=" + !astra + " Notification=true");
            } catch (ReflectiveOperationException | RuntimeException error) {
                Logx.e(CourseAlarmEntry.TAG, "course Seedling host routing failed", error);
            }
        }
    }
}
