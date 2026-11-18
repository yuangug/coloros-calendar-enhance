package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import io.github.libxposed.api.*;

/** Hosts real material Views without changing the native timetable's hit testing. */
final class CourseSurfaceRenderer {
    private static final WeakHashMap<View, Session> SESSIONS = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Header> HEADERS = new WeakHashMap<>();
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DRAWING_HEADER = new ThreadLocal<>();
    private static Method computeRect, drawHeader;
    private static Field highlightWeekdays;
    private static boolean failureLogged;
    static void install(XposedModule module, ClassLoader host) {
        Class<?> cls = Ref.cls(host, "com.android.calendar.card.schooltimetable.view.TableView");
        if (cls == null) return;
        installContentClip(module, cls);
        if (Build.VERSION.SDK_INT < 33) return;
        computeRect = Ref.methodByArity(cls, "drawCourse$computeRectF", 2);
        drawHeader = Ref.methodOf(cls, "drawTopTitle", Canvas.class);
        try { highlightWeekdays = cls.getDeclaredField("shouldHighlightWeekdays"); highlightWeekdays.setAccessible(true); }
        catch (ReflectiveOperationException error) { fail(error); return; }
        Method item = Ref.methodByArity(cls, "drawCourse$drawCourseItem", 6);
        Method courses = Ref.methodOf(cls, "drawCourse", Canvas.class);
        if (computeRect == null || item == null || courses == null || drawHeader == null) return;
        module.hook(drawHeader).setId("course_fixed_header")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Session session = SESSIONS.get(chain.getThisObject());
            if (session == null || Boolean.TRUE.equals(DRAWING_HEADER.get())) return chain.proceed();
            session.header.invalidate();
            return null;
        });
        module.hook(courses).setId("course_surface_frame").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View table = (View)chain.getThisObject();
            Canvas canvas = (Canvas)chain.getArg(0);
            Session session = null;
            if (enabled(table, canvas)) {
                session = session(table);
                if (session != null) session.begin();
            } else {
                Session old = SESSIONS.remove(table); if (old != null) old.release();
            }
            try { return chain.proceed(); }
            finally { if (session != null) session.end(); }
        });
        module.hook(item).setId("course_material_surface").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View table = (View)chain.getArg(0);
            Canvas canvas = (Canvas)chain.getArg(2);
            Session session = SESSIONS.get(table);
            if (session == null || !enabled(table, canvas)) return chain.proceed();
            Object course = chain.getArg(4);
            RectF rect = (RectF)computeRect.invoke(null, table, course);
            if (rect.width() < 1 || rect.height() < 1) return chain.proceed();
            Card card;
            try { card = session.card(course, rect); }
            catch (Exception error) { fail(error); return chain.proceed(); }
            Capture capture = new Capture(session.style != ThemeStore.STYLE_CLASSIC);
            RecordingCanvas foreground = card.foreground.beginRecording(Math.max(1,(int)Math.ceil(rect.width())), Math.max(1,(int)Math.ceil(rect.height())));
            foreground.translate(-rect.left, -rect.top);
            CAPTURE.set(capture);
            try {
                Object[] args = new Object[]{chain.getArg(0),chain.getArg(1),foreground,chain.getArg(3),course,chain.getArg(5)};
                return chain.proceed(args);
            } finally {
                CAPTURE.remove(); capture.restore(); card.foreground.endRecording();
                card.seen = capture.fills >= 2;
                card.setVisibility(card.seen ? View.VISIBLE : View.INVISIBLE);
                card.invalidate();
            }
        });
        Method color = Ref.methodOf(Paint.class, "setColor", int.class);
        module.hook(color).setId("course_surface_remove_fills").intercept(chain -> {
            Capture capture = CAPTURE.get();
            if (capture != null && capture.fills < 2) {
                capture.fills++;
                if (capture.suppressFills) { capture.colors.put((Paint)chain.getThisObject(),(Integer)chain.getArg(0)); return chain.proceed(new Object[]{0}); }
            }
            return chain.proceed();
        });
        Method alpha = Ref.methodOf(Paint.class, "setAlpha", int.class);
        module.hook(alpha).setId("course_surface_keep_transparent").intercept(chain -> {
            Capture capture=CAPTURE.get(); Paint paint=(Paint)chain.getThisObject();
            if (capture!=null && capture.colors.containsKey(paint)) { capture.alphas.put(paint,(Integer)chain.getArg(0));return chain.proceed(new Object[]{0}); }
            return chain.proceed();
        });
        Logx.i(CourseAlarmEntry.TAG, "课程材质: 原生 COUI View / Backdrop GPU 已接入");
    }
    private static boolean enabled(View table, Canvas canvas) {
        return canvas.isHardwareAccelerated()
                && !Boolean.TRUE.equals(Ref.field(table, "isShareMode"));
    }
    private static boolean belongsToPage(View table) {
        View root = HostTablePage.rootView();
        if (root == null) return false;
        ViewParent parent = table.getParent();
        while (parent != null && parent != root) parent = parent.getParent();
        return parent == root;
    }
    private static void installContentClip(XposedModule module, Class<?> cls) {
        for (String name : new String[]{"drawContent", "drawLeftAxisArea", "drawCourse", "drawTouchRect", "drawDragArea"}) {
            Method method = Ref.methodOf(cls, name, Canvas.class);
            if (method == null) continue;
            module.hook(method).setId("course_body_clip_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                View table = (View)chain.getThisObject();
                if (!belongsToPage(table)) return chain.proceed();
                Canvas canvas = (Canvas)chain.getArg(0);
                float scroll = number(Ref.field(table, "scrollY"));
                int save = canvas.save();
                // Native onDraw has already translated the Canvas by -scrollY.
                // Clip every scrolling pass, including the time axis and drag handles.
                canvas.clipRect(0, number(Ref.field(table, "contentStartY")) + scroll,
                        table.getWidth(), table.getHeight() + scroll);
                try { return chain.proceed(); }
                finally { canvas.restoreToCount(save); }
            });
        }
    }
    private static Session session(View table) {
        Session old = SESSIONS.get(table); if (old != null) return old;
        View root = HostTablePage.rootView();
        if (!(root instanceof ViewGroup) || !table.isAttachedToWindow()) return null;
        ViewParent parent = table.getParent();
        while (parent != null && parent != root) parent = parent.getParent();
        if (parent != root) return null;
        Session created = new Session(table, (ViewGroup)root); SESSIONS.put(table,created); return created;
    }
    private static void fail(Exception error) {
        if (!failureLogged) { failureLogged=true; Logx.e(CourseAlarmEntry.TAG,"课程材质不可用，保持普通卡片",error); }
    }
    private static float number(Object object) { return object instanceof Number ? ((Number)object).floatValue() : 0; }
    static void invalidateHeader(View root) {
        Header header = HEADERS.get(root);
        if (header != null) header.invalidate();
    }
    static boolean hasSurface(View table) {
        Session session = SESSIONS.get(table);
        return session != null && !session.released;
    }
    private static final class Capture {
        final boolean suppressFills;
        Capture(boolean suppressFills) { this.suppressFills = suppressFills; }
        int fills; final IdentityHashMap<Paint,Integer> colors=new IdentityHashMap<>(),alphas=new IdentityHashMap<>();
        void restore(){for(Map.Entry<Paint,Integer> entry:colors.entrySet()){entry.getKey().setColor(entry.getValue());if(alphas.containsKey(entry.getKey()))entry.getKey().setAlpha(alphas.get(entry.getKey()));}}
    }
    private static final class Session implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> table;
        private final WeakReference<ViewGroup> root;
        private final SurfaceLayer layer;
        private final GlassScene scene;
        private final Header header;
        private final IdentityHashMap<Object,Card> cards = new IdentityHashMap<>();
        private final Matrix contentToRoot = new Matrix();
        private final Matrix headerToRoot = new Matrix();
        private final RectF viewport = new RectF();
        private int style;
        private boolean released;
        Session(View table, ViewGroup root) {
            this.table = new WeakReference<>(table); this.root = new WeakReference<>(root);
            layer = new SurfaceLayer(table.getContext()); scene = new GlassScene(root);
            root.getOverlay().add(layer); table.addOnAttachStateChangeListener(this);
            Header existing = HEADERS.get(root);
            if (existing == null) { existing = new Header(root); HEADERS.put(root, existing); }
            header = existing;
            // New adjacent pages must always stay below the fixed title/week text.
            root.getOverlay().remove(header);
            root.getOverlay().add(header);
            table.getViewTreeObserver().addOnPreDrawListener(this);
            style = ThemeStore.style(table.getContext()); updateTransform();
        }
        void begin() {
            View tableView=table.get(); if (tableView == null) return;
            int next=ThemeStore.style(tableView.getContext());
            if (style != next) { clearCards(); style=next; }
            updateTransform();
            if (style == ThemeStore.STYLE_LIQUID) scene.capture(tableView.getDrawingTime());
            for (Card card : cards.values()) card.seen=false;
        }
        Card card(Object entity, RectF rect) throws Exception {
            View tableView=table.get();
            Card card=cards.get(entity);
            if (card==null) {
                card=new Card(tableView.getContext(),style,scene); cards.put(entity,card); layer.addView(card);
            }
            card.entity=entity; card.rect.set(rect); card.radius=Math.min(number(Ref.field(tableView,"courseRadius")),Math.min(rect.width(),rect.height())/2);
            int courseColor=((Number)Ref.callName(entity,"getColor")).intValue();
            if (card.paletteIndex!=courseColor) {
                Class<?> cls=Class.forName("com.oplus.todotaskability.TodoColor",false,tableView.getContext().getClassLoader());
                Object token=cls.getMethod("createByIndex",Integer.class).invoke(null,courseColor);
                int colorId=((Number)Ref.callName(token,"getBgColor")).intValue();
                card.courseColor=tableView.getResources().getColor(colorId,tableView.getContext().getTheme()); card.paletteIndex=courseColor;
            }
            position(card); return card;
        }
        void end() {
            Iterator<Map.Entry<Object,Card>> iterator=cards.entrySet().iterator();
            while (iterator.hasNext()) { Card card=iterator.next().getValue();
                if (!card.seen) { card.release();layer.removeView(card);iterator.remove(); } else position(card);
            }
            layer.invalidate();
        }
        private void updateTransform() {
            View t=table.get(); ViewGroup r=root.get(); if(t==null||r==null) return;
            layer.layout(0,0,r.getWidth(),r.getHeight());
            Matrix viewToRoot=new Matrix();t.transformMatrixToGlobal(viewToRoot);r.transformMatrixToLocal(viewToRoot);
            boolean moved = !headerToRoot.equals(viewToRoot);
            headerToRoot.set(viewToRoot);
            RectF headerBounds = new RectF(0, 0, t.getWidth(), number(Ref.field(t,"contentStartY")));
            viewToRoot.mapRect(headerBounds);
            viewport.set(0,0,t.getWidth(),t.getHeight());viewToRoot.mapRect(viewport);
            // Only course cards continue under the fixed header. The native grid and
            // time axis are clipped by installContentClip and cannot cover its text.
            viewport.top = 0;
            boolean intersects = viewport.intersect(0, 0, r.getWidth(), r.getHeight());
            if (!intersects) viewport.setEmpty();
            layer.setViewport(viewport);
            contentToRoot.set(viewToRoot);
            if(Boolean.TRUE.equals(Ref.field(t,"isRtl"))) contentToRoot.preScale(-1,1,t.getWidth()/2f,t.getHeight()/2f);
            contentToRoot.preTranslate(0,-number(Ref.field(t,"scrollY")));
            boolean shown=intersects && t.isShown() && t.getWindowVisibility()==View.VISIBLE;
            layer.setVisibility(shown?View.VISIBLE:View.INVISIBLE);
            if (shown) header.update(Math.max(0, (int)Math.ceil(headerBounds.bottom)));
            if (moved) header.invalidate();
        }
        private void position(Card card) {
            RectF placement=new RectF(card.rect);
            View tableView=table.get();
            boolean dragging=tableView!=null && CourseDragController.previewRect(tableView,card.entity,placement);
            Matrix transform=new Matrix(contentToRoot);transform.preTranslate(placement.left,placement.top);
            int width=Math.max(1,(int)Math.ceil(card.rect.width())),height=Math.max(1,(int)Math.ceil(card.rect.height()));
            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            card.layout(0,0,width,height);
            if(dragging){
                float scroll=number(Ref.field(tableView,"scrollY"));
                int top=Math.max(0,(int)Math.ceil(number(Ref.field(tableView,"contentStartY"))+scroll-placement.top));
                int bottom=Math.min(height,(int)Math.floor(CourseDragController.previewBottom(tableView)+scroll-placement.top));
                int left=Math.max(0,(int)Math.ceil(number(Ref.field(tableView,"contentStartX"))-placement.left));
                int right=Math.min(width,(int)Math.floor(tableView.getWidth()-placement.left));
                left=Math.min(left,width);top=Math.min(top,height);
                card.setClipBounds(new Rect(left,top,Math.max(left,right),Math.max(top,bottom)));
                if(layer.indexOfChild(card)!=layer.getChildCount()-1)card.bringToFront();
            } else card.setClipBounds(null);
            if (!card.cardToRoot.equals(transform)) { card.setAnimationMatrix(transform); card.cardToRoot.set(transform); card.mappingChanged=true; }
            // Clip in the layer's coordinates, including partially visible first/last rows.
            try { card.update(); } catch(Exception error) { fail(error);card.setBackgroundColor(card.courseColor); }
        }
        @Override public boolean onPreDraw() {
            if(released)return true;
            View t=table.get();if(t==null)return true;
            if (!t.isShown() || t.getWindowVisibility()!=View.VISIBLE) {
                layer.setVisibility(View.INVISIBLE);
                return true;
            }
            if(style!=ThemeStore.style(t.getContext())){clearCards();style=ThemeStore.style(t.getContext());t.invalidate();}
            updateTransform();
            if (layer.getVisibility() == View.VISIBLE) {
                // Offscreen pages can retain their TableView display list while HWUI
                // discards our unreferenced foreground nodes. Re-record through the
                // native draw hook before this frame instead of waiting for a touch.
                for (Card card : cards.values()) {
                    if (!card.foreground.hasDisplayList()) { t.invalidate(); break; }
                }
            }
            if (layer.getVisibility() == View.VISIBLE) {
                if(style==ThemeStore.STYLE_LIQUID)scene.capture(t.getDrawingTime());
                for(Card card:cards.values())position(card);
                if(scene.animated())layer.postInvalidateOnAnimation();
            }
            return true;
        }
        private void clearCards(){for(Card card:cards.values())card.release();cards.clear();layer.removeAllViews();}
        void release(){if(released)return;released=true;clearCards();scene.release();View t=table.get();ViewGroup r=root.get();
            if(t!=null){t.removeOnAttachStateChangeListener(this);if(t.getViewTreeObserver().isAlive())t.getViewTreeObserver().removeOnPreDrawListener(this);}
            if(r!=null){
                r.getOverlay().remove(layer);
                boolean inUse=false;
                for(Session session:SESSIONS.values())if(!session.released&&session.root.get()==r){inUse=true;break;}
                if(!inUse){header.release();HEADERS.remove(r);r.getOverlay().remove(header);}
            }
        }
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v){release();SESSIONS.remove(v);}
    }
    /** Fixed native text over the same background, without blur or a material capsule. */
    private static final class Header extends View {
        final WeakReference<ViewGroup> root;
        final WeakReference<View> title;
        final ThemeWindowBackground backdrop;
        final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
        Header(ViewGroup root) {
            super(root.getContext()); this.root=new WeakReference<>(root);
            View heading=HostTablePage.headerView(); title=new WeakReference<>(heading);
            if(heading!=null)heading.setVisibility(View.INVISIBLE);
            setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);setForceDarkAllowed(false);
            backdrop = new ThemeWindowBackground(this, true);
            fade.setBlendMode(BlendMode.DST_IN);
        }
        void update(int height) {
            ViewGroup r=root.get();if(r==null)return;
            View heading=title.get();if(heading!=null)height=Math.max(height,heading.getBottom());
            int width=r.getWidth();height=Math.min(height,r.getHeight());
            if(getWidth()!=width||getHeight()!=height){
                measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
                layout(0,0,width,height);
                backdrop.setBounds(0,0,width,height);
                fade.setShader(new LinearGradient(0,0,0,Math.max(1,height),
                        new int[]{0xC8000000,0xA8000000,0x00000000},new float[]{0f,0.84f,1f},Shader.TileMode.CLAMP));
                invalidate();
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            ViewGroup r=root.get();if(r==null)return;
            // Redraw the exact aligned background with a fading alpha. This softens
            // only the courses underneath; the picture itself has no tint or blur.
            int backdropSave=canvas.saveLayer(0,0,getWidth(),getHeight(),null);
            backdrop.draw(canvas);
            canvas.drawRect(0,0,getWidth(),getHeight(),fade);
            canvas.restoreToCount(backdropSave);
            View heading=title.get();
            if(heading!=null){int save=canvas.save();canvas.translate(heading.getLeft(),heading.getTop());heading.draw(canvas);canvas.restoreToCount(save);}
            for(Session session:SESSIONS.values()){
                if(session.released||session.root.get()!=r||session.layer.getVisibility()!=View.VISIBLE)continue;
                View table=session.table.get();if(table==null)continue;
                int save=canvas.save();
                canvas.clipRect(0,heading==null?0:heading.getBottom(),getWidth(),getHeight());
                canvas.concat(session.headerToRoot);
                if(Boolean.TRUE.equals(Ref.field(table,"isRtl")))canvas.scale(-1,1,table.getWidth()/2f,table.getHeight()/2f);
                DRAWING_HEADER.set(true);
                boolean highlighted=Boolean.TRUE.equals(Ref.field(table,"shouldHighlightWeekdays"));
                try{highlightWeekdays.setBoolean(table,false);drawHeader.invoke(table,canvas);}catch(Exception error){fail(error);}
                finally{
                    try{highlightWeekdays.setBoolean(table,highlighted);}catch(IllegalAccessException error){fail(error);}
                    DRAWING_HEADER.remove();canvas.restoreToCount(save);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){return false;}
        void release(){View heading=title.get();if(heading!=null)heading.setVisibility(View.VISIBLE);}
    }
    private static final class SurfaceLayer extends ViewGroup {
        final RectF viewport=new RectF();
        SurfaceLayer(Context context){super(context);setClipChildren(false);setClipToPadding(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
        void setViewport(RectF next) {
            if (viewport.equals(next)) return;
            viewport.set(next);
            // dispatchDraw records this clip in the layer's display list. Moving a page
            // only updates the cards' RenderNode transforms, so the clip must also be
            // recorded again in this traversal, including the final settling frame.
            invalidate();
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){ }
        @Override protected void dispatchDraw(Canvas canvas){int save=canvas.save();canvas.clipRect(viewport);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){return false;}
    }
    private static final class Card extends View {
        final RenderNode foreground=new RenderNode("course-native-foreground");
        final RectF rect=new RectF();final Matrix cardToRoot=new Matrix();
        Object entity;
        final GlassScene scene;final LiquidCardEffect liquid;final NativeCardMaterial nativeMaterial;
        int paletteIndex=Integer.MIN_VALUE,courseColor;float radius;boolean seen,mappingChanged=true;
        int lastWidth=-1,lastHeight=-1,lastGeneration=-1,lastConfig;float lastRadius=-1;
        Card(Context context,int style,GlassScene scene)throws Exception{
            super(context);this.scene=scene;setWillNotDraw(false);setClickable(false);setFocusable(false);setForceDarkAllowed(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if(style==ThemeStore.STYLE_LIQUID){liquid=new LiquidCardEffect(getResources().getDisplayMetrics().density);nativeMaterial=null;}
            else if(style==ThemeStore.STYLE_NINGGUANG){liquid=null;nativeMaterial=new NativeCardMaterial(this);}
            else{liquid=null;nativeMaterial=null;}
            setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,getWidth(),getHeight(),radius);}});
        }
        void update()throws Exception{
            int config=getResources().getConfiguration().hashCode();
            boolean geometry=lastWidth!=getWidth()||lastHeight!=getHeight()||lastRadius!=radius;
            if(!geometry&&!mappingChanged&&lastGeneration==scene.generation&&lastConfig==config
                    && (liquid==null || !liquid.needsRecording()))return;
            foreground.setPosition(0,0,getWidth(),getHeight());
            if(geometry)invalidateOutline();
            if(liquid!=null)liquid.update(getWidth(),getHeight(),radius,cardToRoot,scene);
            if(nativeMaterial!=null && (geometry || lastConfig!=config)){nativeMaterial.update(radius);setClipToOutline(true);invalidateOutline();}
            lastWidth=getWidth();lastHeight=getHeight();lastRadius=radius;lastGeneration=scene.generation;lastConfig=config;mappingChanged=false;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            if(liquid!=null)liquid.draw(canvas,courseColor);
            if(nativeMaterial!=null)nativeMaterial.drawEdge(canvas);
            canvas.drawRenderNode(foreground);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(nativeMaterial!=null)try{nativeMaterial.attach();}catch(Exception error){fail(error);}}
        @Override protected void onDetachedFromWindow(){if(nativeMaterial!=null)nativeMaterial.release();super.onDetachedFromWindow();}
        void release(){foreground.discardDisplayList();if(liquid!=null)liquid.release();if(nativeMaterial!=null)nativeMaterial.release();}
        @Override public boolean onTouchEvent(MotionEvent event){return false;}
    }
}
