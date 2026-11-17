package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.*;
import java.lang.reflect.*;

/** Calls the same COUI/Oplus View effects as BaseBottomFloatingMenuView. */
final class NativeCardMaterial {
    private final View view;
    private final ClassLoader loader;
    private final Class<?> effect;
    private final Object manager;
    private Object listener;
    private Drawable edge;
    private float radius;
    private int configuration, width=-1, height=-1;
    private boolean attached;
    NativeCardMaterial(View view) throws Exception {
        this.view = view; loader = view.getContext().getClassLoader();
        effect = Class.forName("com.coui.appcompat.COUIMaterialEffect", false, loader);
        Class<?> state = Class.forName("com.coui.appcompat.COUIMaterialStateManager", false, loader);
        manager = state.getField("INSTANCE").get(null);
        if (!Boolean.TRUE.equals(effect.getMethod("isMaterialVersion").invoke(null))) throw new IllegalStateException("宿主原生材质版本不支持");
    }
    void attach() throws Exception {
        if (attached) return;
        Class<?> iface = Class.forName("com.coui.appcompat.OnMaterialStateChangeListener", false, loader);
        listener = Proxy.newProxyInstance(loader, new Class[]{iface}, (proxy, method, args) -> {
            if (method.getName().equals("onMaterialStateChange")) { configuration = 0; update(radius); view.invalidate(); }
            if (method.getName().equals("onDump") || method.getName().equals("toString")) return "CourseCardMaterial";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
        manager.getClass().getMethod("addOnMaterialStateChangedListener", iface).invoke(manager, listener);
        attached = true;
    }
    void update(float corner) throws Exception {
        int key = view.getResources().getConfiguration().hashCode();
        if (configuration == key && radius == corner && width==view.getWidth() && height==view.getHeight()) return;
        width=view.getWidth();height=view.getHeight();
        radius = corner; configuration = key;
        Context context = view.getContext();
        boolean enabled = Boolean.TRUE.equals(Ref.callName(manager, "isBlurEffectEnable"));
        boolean drawable = findDrawableBranch(view.getRootView());
        applyEnum("setViewBlurEffect", "COUIMaterialBlurEffect$BlurEffectType", enabled && !drawable ? "TYPE_FRAMEWORK_BOTTOM_BAR" : "TYPE_NO_EFFECT");
        boolean stroke = Boolean.TRUE.equals(Ref.callName(manager, "isStrokeEffectEnable"));
        if (drawable && stroke) {
            applyEnum("setViewStrokeEffect", "COUIMaterialStrokeEffect$StrokeEffectType", "TYPE_NO_EFFECT_BASE_1");
            createEdge(context);
        } else {
            edge = null;
            applyEnum("setViewStrokeEffect", "COUIMaterialStrokeEffect$StrokeEffectType", stroke ? "TYPE_FRAMEWORK_CAPSULE_3" : "TYPE_NO_EFFECT");
        }
        view.setBackgroundColor(enabled && !drawable ? 0 : (NingGuang.night(context) ? 0xE8303030 : 0xE8FFFFFF));
        Class<?> corners = Class.forName("com.oplus.view.material.OplusMaterialCornerParams", false, loader);
        Object params = corners.getConstructor(float.class, float.class).newInstance(corner, 1f);
        corners.getMethod("setRadius",float.class).invoke(params,corner);
        effect.getMethod("setCornerParams", View.class, corners).invoke(null, view, params);
        boolean caustic = Boolean.TRUE.equals(Ref.callName(manager, "isCausticShadowEffectEnable"));
        Class<?> shadow = Class.forName("com.coui.appcompat.uiutil.ShadowUtils", false, loader);
        Method elevation = Ref.methodOf(shadow, "setElevationToView", View.class, int.class);
        if (elevation != null) elevation.invoke(null, view, caustic ? 7 : 2);
        if (caustic) {
            int color = resource("coui_caustic_color_2", "color");
            if (color != 0) effect.getMethod("setOutlineCausticShadowColor", View.class, int.class).invoke(null, view, context.getColor(color));
            int[] offsets=new int[4];String[] sides={"l","t","r","b"};
            for(int i=0;i<4;i++){int id=resource("coui_caustic_layout_"+sides[i]+"_level4","dimen");if(id!=0)offsets[i]=view.getResources().getDimensionPixelOffset(id);}
            effect.getMethod("setOutlineCausticShadowLayout",View.class,int.class,int.class,int.class,int.class).invoke(null,view,offsets[0],offsets[1],offsets[2],offsets[3]);
            int size = resource("coui_caustic_layout_sdf_level4", "dimen");
            if (size != 0) effect.getMethod("setOutlineCausticShadowParams", View.class, float.class).invoke(null, view, view.getResources().getDimension(size));
        } else effect.getMethod("clearCausticShadow", View.class).invoke(null, view);
        // Reapply after stroke/shadow presets, which can reset material geometry.
        effect.getMethod("setCornerParams", View.class, corners).invoke(null, view, params);
        if(view.getClass().getName().contains("CourseSurfaceRenderer$Card")){
            effect.getMethod("setCornerParams",View.class,corners).invoke(null,view,null);
            view.setClipToOutline(true);
        }
        view.invalidateOutline();
        if (edge != null) edge.setCallback(view);
    }
    private void createEdge(Context context) throws Exception {
        Class<?> cls = Class.forName("com.coui.appcompat.shadowedge.COUIShadowEdgeDrawable", false, loader);
        edge = (Drawable) cls.getConstructor().newInstance();
        Class<?> type = Class.forName("com.coui.appcompat.COUIMaterialStrokeEffect$StrokeEffectType", false, loader);
        Object preset = Enum.valueOf((Class)type, "TYPE_FRAMEWORK_CAPSULE_12");
        Object params = effect.getMethod("getStrokeParamsByType", Context.class, type).invoke(null, context, preset);
        Ref.callName(edge, "setEdgeEnable", true); Ref.callName(edge, "setShadowEnable", true);
        Ref.callName(edge, "setLineAlpha", Ref.callName(params, "getEdgeAlpha"));
        Ref.callName(edge, "setLineWidth", Ref.callName(params, "getEdgeWidth", context));
        Ref.callName(edge, "setLineAngle", Ref.callName(params, "getEdgeAngle"));
        Ref.callName(edge, "setFadeAlpha", Ref.callName(params, "getShadowFadeIn"), Ref.callName(params, "getShadowFadeOut"));
        Ref.callName(edge, "setFadeWidthScale", Ref.callName(params, "getShadowFadeScale"));
        Ref.callName(edge, "setIsDarkMode", NingGuang.night(context));
        Ref.callName(edge, "setCornerType", 2); Ref.callName(edge, "setWeight", 1f);
        Ref.callName(edge, "setPaintBlendMode", android.graphics.BlendMode.SRC_OVER);
    }
    private boolean findDrawableBranch(View root) {
        if (root.getClass().getName().contains("BottomFloating") && Ref.field(root, "mIsDrawableMaterialEffect") instanceof Boolean)
            return Boolean.TRUE.equals(Ref.field(root, "mIsDrawableMaterialEffect"));
        if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++)
            if (findDrawableBranch(((ViewGroup)root).getChildAt(i))) return true;
        return false;
    }
    private int resource(String name, String type) { return view.getResources().getIdentifier(name, type, view.getContext().getPackageName()); }
    private void applyEnum(String method, String cls, String value) throws Exception {
        Class<?> type = Class.forName("com.coui.appcompat." + cls, false, loader);
        effect.getMethod(method, View.class, type).invoke(null, view, Enum.valueOf((Class)type, value));
    }
    void drawEdge(Canvas canvas) {
        if (edge == null) return;
        edge.setBounds(0, 0, view.getWidth(), view.getHeight()); Ref.callName(edge, "setCornerRadius", radius); edge.draw(canvas);
    }
    void release() {
        if (attached && listener != null) Ref.callName(manager, "removeOnMaterialStateChangedListener", listener);
        try { effect.getMethod("clearBlurEffect", View.class).invoke(null, view); effect.getMethod("clearCausticShadow", View.class).invoke(null, view); } catch (Exception ignored) { }
        if (edge != null) edge.setCallback(null);
        listener = null; attached = false;
    }
}
