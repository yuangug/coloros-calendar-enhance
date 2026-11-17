package com.lcodexs.coursealarm;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.content.Context;
import android.util.TypedValue;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;

/** One undo deadline across host Activity transitions; no Activity is retained. */
final class UndoBar implements Application.ActivityLifecycleCallbacks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static View bar;
    private static String token, message;
    private static long deadline;
    private static boolean installed, restoring;
    static void init(Application app) {
        if (installed) return;
        installed = true; app.registerActivityLifecycleCallbacks(new UndoBar());
    }
    static void resumed(Activity activity) { resumed = new WeakReference<>(activity); attach(); }
    static void paused(Activity activity) { if (resumed.get() == activity) { remove(); resumed.clear(); } }
    static void show(String text, String value) {
        remove(); token = value; message = text; deadline = 0; restoring = false; attach();
    }
    private static void attach() {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || token == null) return;
        if (deadline != 0 && SystemClock.elapsedRealtime() >= deadline && !restoring) { expire(); return; }
        remove();
        float density = activity.getResources().getDisplayMetrics().density;
        MaterialRow row = new MaterialRow(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18*density), (int)(2*density), (int)(10*density), (int)(2*density));

        TextView label = new TextView(activity); label.setText(restoring ? "正在撤回…" : message); label.setTextColor(themeColor(activity,android.R.attr.textColorPrimary,NingGuang.night(activity)?Color.WHITE:0xFF191919)); label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView action = new TextView(activity); action.setText("撤回"); action.setTextColor(accentColor(activity)); action.setTextSize(15);
        action.setGravity(Gravity.CENTER); action.setMinHeight((int)(44*density)); action.setMinWidth((int)(64*density));
        action.setEnabled(!restoring); row.addView(action);
        action.setOnClickListener(v -> {
            if (restoring || deadline == 0 || SystemClock.elapsedRealtime() >= deadline) return;
            restoring = true; action.setEnabled(false); label.setText("正在撤回…"); CourseMutations.undo(token);
        });
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = (int)(16*density);
        int inset = 0;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) inset = insets.getSystemWindowInsetBottom();
        lp.bottomMargin = inset + (int)(88*density);
        decor.addView(row, lp); bar = row;
        if (deadline == 0) deadline = SystemClock.elapsedRealtime() + 3000;
        CourseMutations.displayed(token, deadline);
        String displayed = token;
        MAIN.postDelayed(() -> { if (displayed.equals(token) && !restoring) expire(); }, Math.max(0, deadline - SystemClock.elapsedRealtime()));
        row.postInvalidateOnAnimation();
        row.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }
    private static int accentColor(Context context){
        int attr=context.getResources().getIdentifier("couiColorPrimary","attr",context.getPackageName());
        int color=themeColor(context,attr!=0?attr:android.R.attr.colorAccent,0xFF0066CC);
        // Some host page themes deliberately use white accents for toolbar icons.
        if(!NingGuang.night(context)&&Color.red(color)>230&&Color.green(color)>230&&Color.blue(color)>230)color=0xFF0066CC;
        return color;
    }
    private static int themeColor(Context context,int attr,int fallback) {
        TypedValue value=new TypedValue();
        if(!context.getTheme().resolveAttribute(attr,value,true))return fallback;
        try{return value.resourceId!=0?context.getColor(value.resourceId):value.data;}catch(Exception e){return fallback;}
    }
    private static final class MaterialRow extends LinearLayout {
        private NativeCardMaterial material;
        private final Paint progress=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ring=new Path(),remainingRing=new Path();
        private final PathMeasure perimeter=new PathMeasure();
        private int ringWidth=-1,ringHeight=-1;
        private final float density;
        MaterialRow(Context context){super(context);density=getResources().getDisplayMetrics().density;setWillNotDraw(false);setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,getWidth(),getHeight(),22*density);}});
            try{material=new NativeCardMaterial(this);}catch(Exception error){fallback();Logx.w(CourseAlarmEntry.TAG,"撤回栏使用宿主普通背景: "+error);}
        }
        private void fallback(){GradientDrawable bg=new GradientDrawable();bg.setColor(themeColor(getContext(),android.R.attr.colorBackground,NingGuang.night(getContext())?0xFF303030:0xFFF8F8F8));bg.setCornerRadius(22*density);setBackground(bg);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(material!=null)try{material.attach();material.update(22*density);}catch(Exception e){material.release();material=null;fallback();}}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);invalidateOutline();if(material!=null)try{material.update(22*density);}catch(Exception ignored){}}
        @Override protected void dispatchDraw(Canvas canvas){
            if(material!=null)material.drawEdge(canvas);super.dispatchDraw(canvas);
            if(deadline==0||restoring)return;
            float fraction=Math.max(0,Math.min(1,(deadline-SystemClock.elapsedRealtime())/3000f));
            if(ringWidth!=getWidth()||ringHeight!=getHeight()){
                ringWidth=getWidth();ringHeight=getHeight();float inset=1.5f*density,r=Math.min(22*density,ringHeight/2f)-inset;
                float l=inset,t=inset,right=ringWidth-inset,b=ringHeight-inset;
                ring.reset();ring.moveTo(ringWidth/2f,t);ring.lineTo(right-r,t);ring.arcTo(right-2*r,t,right,t+2*r,-90,90,false);
                ring.lineTo(right,b-r);ring.arcTo(right-2*r,b-2*r,right,b,0,90,false);ring.lineTo(l+r,b);
                ring.arcTo(l,b-2*r,l+2*r,b,90,90,false);ring.lineTo(l,t+r);ring.arcTo(l,t,l+2*r,t+2*r,180,90,false);ring.close();
                perimeter.setPath(ring,true);
            }
            progress.setStyle(Paint.Style.STROKE);progress.setStrokeWidth(1.1f*density);progress.setStrokeCap(Paint.Cap.ROUND);
            progress.setColor(accentColor(getContext()));progress.setAlpha(28);canvas.drawPath(ring,progress);
            remainingRing.reset();perimeter.getSegment(0,perimeter.getLength()*fraction,remainingRing,true);
            progress.setAlpha(210);canvas.drawPath(remainingRing,progress);
            if(fraction>0&&isAttachedToWindow())postInvalidateOnAnimation();
        }
        @Override protected void onDetachedFromWindow(){if(material!=null)material.release();super.onDetachedFromWindow();}
    }
    private static void expire() { String expired = token; token = null; remove(); if (expired != null) CourseMutations.expire(expired); }
    static void finish(String expectedToken, String text) {
        if (!java.util.Objects.equals(expectedToken, token)) return;
        Activity activity = resumed.get(); token = null; restoring = false; remove();
        if (activity != null) CurriculumIo.toast(activity, text);
    }
    private static void remove() { if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup)bar.getParent()).removeView(bar); bar = null; }
    @Override public void onActivityResumed(Activity a) { resumed(a); }
    @Override public void onActivityPaused(Activity a) { paused(a); }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { if (resumed.get() == a) { remove(); resumed.clear(); } }
}
