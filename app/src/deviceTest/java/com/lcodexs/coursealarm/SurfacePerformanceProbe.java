package com.lcodexs.coursealarm;

import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONObject;

/** Verification build only: measure surface churn without modifying course data. */
final class SurfacePerformanceProbe {
    static void sample(int requested) throws Exception {
        int frames = Math.max(1, Math.min(120, requested));
        Field field = CourseSurfaceRenderer.class.getDeclaredField("SESSIONS");
        field.setAccessible(true);
        ArrayList<View> tables = new ArrayList<>();
        ArrayList<ViewGroup> layers = new ArrayList<>();
        int[] changes = new int[2];
        for (Object session : ((Map<?, ?>)field.get(null)).values()) {
            ViewGroup layer = (ViewGroup)Ref.field(session, "layer");
            View table = (View)((WeakReference<?>)Ref.field(session, "table")).get();
            if (table == null || layer.getVisibility() != View.VISIBLE) continue;
            tables.add(table); layers.add(layer);
            layer.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                public void onChildViewAdded(View parent, View child) { changes[0]++; }
                public void onChildViewRemoved(View parent, View child) { changes[1]++; }
            });
        }
        if (tables.isEmpty()) throw new IllegalStateException("No visible course surface");
        View driver = tables.get(0);
        driver.postOnAnimation(new Runnable() {
            int step;
            public void run() {
                if (step++ < frames) {
                    for (View table : tables) table.invalidate();
                    driver.postOnAnimation(this);
                    return;
                }
                int retained = 0;
                for (ViewGroup layer : layers) {
                    layer.setOnHierarchyChangeListener(null);
                    retained += layer.getChildCount();
                }
                try { DeviceVerification.reply(new JSONObject().put("requestedFrames",frames)
                        .put("tables",tables.size()).put("added",changes[0])
                        .put("removed",changes[1]).put("retained",retained),null); }
                catch (Exception error) { DeviceVerification.reply(null,error); }
            }
        });
    }
}
