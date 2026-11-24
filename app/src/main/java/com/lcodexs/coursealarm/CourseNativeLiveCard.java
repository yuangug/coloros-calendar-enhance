package com.lcodexs.coursealarm;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.libxposed.api.XposedModule;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Decorates the existing verified Calendar Seedling row; never publishes a notification. */
final class CourseNativeLiveCard {
    private static final String CALENDAR = "com.coloros.calendar";
    private static final String SERVICE = "268452047";
    private static final String CHANGED = "io.github.yuangug.coursealarm.NATIVE_CARD_CHANGED";
    private static final Uri PROVIDER = Uri.parse("content://com.coloros.calendar.CourseContentProvider");
    private static final String ROW = "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow";
    private static final String ENTRY = "com.android.systemui.plugins.custom.CustomRowEntry";
    private static final long[] STATE_RETRY_DELAY = {200L, 600L, 1500L};
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<View, WeakReference<Session>> sessions = new WeakHashMap<>();
    private static Handler worker;
    private static Context app;
    private static boolean installed;
    private static long lastBroadcast;

    static void install(XposedModule module, ClassLoader loader, Context context) {
        if (installed || Build.VERSION.SDK_INT < 33 || !"com.android.systemui".equals(context.getPackageName())) return;
        Context application = context.getApplicationContext();
        app = application != null ? application : context;
        HandlerThread thread = new HandlerThread("CourseNativeCard", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
        installed = true;
        try {
            Class<?> row = Class.forName(ROW, false, loader);
            Class<?> entry = Class.forName(ENTRY, false, loader);
            Method setEntry = row.getDeclaredMethod("setEntry", entry);
            Method setView = row.getDeclaredMethod("setNewSeedlingView", View.class);
            if (!ViewGroup.class.isAssignableFrom(row)
                    || row.getDeclaredMethod("getEntry").getReturnType() != entry
                    || entry.getDeclaredMethod("getKey").getReturnType() != String.class
                    || entry.getDeclaredMethod("getPackageName").getReturnType() != String.class
                    || entry.getDeclaredMethod("getServiceId").getReturnType() != String.class)
                throw new NoSuchMethodException("Seedling notification row contract changed");
            module.hook(setEntry).setId("course_native_card_entry_v1").intercept(chain -> {
                Object result = chain.proceed();
                safeAttach((ViewGroup) chain.getThisObject(), false);
                return result;
            });
            module.hook(setView).setId("course_native_card_content_v1").intercept(chain -> {
                Object result = chain.proceed();
                safeAttach((ViewGroup) chain.getThisObject(), true);
                return result;
            });
            Logx.i(CourseAlarmEntry.TAG, "native course row hooks ready");
        } catch (Throwable error) {
            Logx.e(CourseAlarmEntry.TAG, "native course row unavailable", error);
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (!CHANGED.equals(intent.getAction())) return;
                    // No state is accepted from this broadcast. It only invalidates authenticated IPC data.
                    long now = android.os.SystemClock.uptimeMillis();
                    main.removeCallbacks(refreshAttached);
                    main.postDelayed(refreshAttached, Math.max(150L, 1000L - (now - lastBroadcast)));
                    lastBroadcast = now;
                }
            };
            app.registerReceiver(receiver, new IntentFilter(CHANGED), Context.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            Logx.e(CourseAlarmEntry.TAG, "native course refresh unavailable", error);
        }
    }

    private static final Runnable refreshAttached = () -> {
        for (WeakReference<Session> reference : new ArrayList<>(sessions.values())) {
            Session session = reference.get();
            // A closed shade can keep the existing row attached. Opening it need not rebind it.
            if (session != null && session.row.isAttachedToWindow()) session.request();
        }
    };

    private static void safeAttach(ViewGroup row, boolean contentChanged) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> safeAttach(row, contentChanged)); return;
        }
        try {
            Object entry = Ref.callName(row, "getEntry");
            WeakReference<Session> reference = sessions.get(row);
            Session old = reference == null ? null : reference.get();
            String key = text(Ref.callName(entry, "getKey"));
            String instance = instanceFromKey(key);
            boolean valid = CALENDAR.equals(text(Ref.callName(entry, "getPackageName")))
                    && SERVICE.equals(text(Ref.callName(entry, "getServiceId"))) && !instance.isEmpty();
            if (!valid) { if (old != null) old.dispose(); return; }
            if (old == null) {
                old = new Session(row, key, instance);
                sessions.put(row, new WeakReference<>(old));
            } else if (!old.rowKey.equals(key)) {
                old.reset(key, instance);
            } else if (contentChanged) {
                // The same row can reapply its template during a Seedling update.
                old.anchorAttempts = 0;
                old.mountSafely();
            }
            old.request();
        } catch (Throwable error) {
            Logx.w(CourseAlarmEntry.TAG, "native course row bind failed: " + error.getClass().getSimpleName());
        }
    }

    /** Verified SystemUIPlugin Seedling model format, not a text/title heuristic. */
    static String instanceFromKey(String key) {
        return CourseNativeCardContract.instanceFromKey(key);
    }

    private static final class Session implements View.OnAttachStateChangeListener {
        final ViewGroup row;
        String rowKey, instance;
        int generation, requestVersion;
        boolean pending, again, disposed;
        Bundle data;
        FrameLayout divider;
        View nativeLine;
        LinearLayout sectionRoot, actionRow;
        ProgressLine progress;
        TextView button;
        final Map<View, Integer> hidden = new WeakHashMap<>();
        String reported = "";
        int anchorAttempts, stateAttempts;
        final Runnable mountLater = this::mountSafely;
        final Runnable retryState = this::request;
        final Runnable refreshAfterAction = this::request;

        Session(ViewGroup row, String rowKey, String instance) {
            this.row = row; this.rowKey = rowKey; this.instance = instance;
            row.addOnAttachStateChangeListener(this);
        }

        void reset(String key, String serviceInstance) {
            generation++; data = null; reported = ""; anchorAttempts = 0; stateAttempts = 0;
            main.removeCallbacks(retryState);
            unmount(); rowKey = key; instance = serviceInstance;
        }

        Bundle query() {
            Bundle query = new Bundle(); query.putInt("protocol", 1);
            query.putString("serviceInstanceId", instance); query.putString("rowKey", rowKey); return query;
        }

        void request() {
            if (disposed || !row.isAttachedToWindow()) return;
            main.removeCallbacks(retryState);
            if (pending) { again = true; return; }
            pending = true;
            int capturedGeneration = generation, version = ++requestVersion;
            Bundle query = query();
            worker.post(() -> {
                Bundle response = null;
                try { response = app.getContentResolver().call(PROVIDER, CourseNativeCardBridge.QUERY, null, query); }
                catch (Throwable ignored) { }
                Bundle result = response;
                main.post(() -> {
                    pending = false;
                    if (disposed || capturedGeneration != generation || version != requestVersion) {
                        if (again && !disposed) { again = false; request(); } return;
                    }
                    if (valid(result, instance)) {
                        data = result; anchorAttempts = 0; stateAttempts = 0; mountSafely();
                    } else {
                        data = null; unmount();
                        if (!again && stateAttempts < STATE_RETRY_DELAY.length
                                && (result == null || result.getBoolean("retry"))) {
                            main.postDelayed(retryState, STATE_RETRY_DELAY[stateAttempts++]);
                        } else if (!again) report(false, "instance_not_validated");
                    }
                    if (again) { again = false; request(); }
                });
            });
        }

        void mountSafely() {
            if (disposed || !valid(data, instance) || !row.isAttachedToWindow()) return;
            try { mount(); }
            catch (Throwable error) {
                unmount(); report(false, "layout_incompatible");
                Logx.e(CourseAlarmEntry.TAG, "native course progress layout", error);
            }
        }

        void mount() throws Exception {
            Object content = Ref.callName(row, "getContentLayout");
            View root = content instanceof View ? (View) content : row;
            View dividerView = findNamed(root, "divider_container");
            View section = findNamed(root, "section_root_container");
            View line = null;
            if (!(dividerView instanceof FrameLayout) || !vertical(section)) {
                dividerView = null;
                line = findNamed(root, "divider_line");
                section = line != null && line.getParent() instanceof View
                        ? (View) line.getParent() : null;
                if (line != null && (!"DotLineView".equals(line.getClass().getSimpleName())
                        || !vertical(section) || findNamed(section, "content") == null)) line = null;
            }
            if (!(dividerView instanceof FrameLayout) && line == null) {
                unmount();
                if (++anchorAttempts < 4) {
                    main.removeCallbacks(mountLater); main.postDelayed(mountLater, anchorAttempts * 150L);
                } else {
                    report(false, "native_divider_missing");
                    Logx.w(CourseAlarmEntry.TAG, "native course anchors unavailable: " + describe(root, 0, new int[]{0}));
                }
                return;
            }
            if (divider != dividerView || nativeLine != line || sectionRoot != section) {
                unmount();
                divider = (FrameLayout) dividerView; nativeLine = line; sectionRoot = (LinearLayout) section;
            }
            Context context = (nativeLine == null ? divider : nativeLine).getContext();
            boolean showProgress = data.getBoolean("progressEnabled") && System.currentTimeMillis() >= data.getLong("start");
            if (showProgress) {
                if (progress == null) {
                    progress = new ProgressLine(context, this);
                    if (nativeLine != null) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                (LinearLayout.LayoutParams) nativeLine.getLayoutParams());
                        int padding = nativeLine.getPaddingTop() + nativeLine.getPaddingBottom();
                        params.height = Math.max(dp(context, 4) + padding,
                                Math.max(params.height, nativeLine.getMeasuredHeight()));
                        progress.setPadding(nativeLine.getPaddingLeft(), nativeLine.getPaddingTop(),
                                nativeLine.getPaddingRight(), nativeLine.getPaddingBottom());
                        sectionRoot.addView(progress, sectionRoot.indexOfChild(nativeLine), params);
                    } else {
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(context, 4));
                        params.bottomMargin = dimension(context, "divider_normal_padding", 8);
                        divider.addView(progress, params);
                    }
                }
                if (nativeLine != null) hide(nativeLine);
                else {
                    // Native template updates may replace its line.
                    for (int i = 0; i < divider.getChildCount(); i++) {
                        View child = divider.getChildAt(i);
                        if (child != progress && ("divider_line".equals(resourceName(child))
                                || "DotLineView".equals(child.getClass().getSimpleName()))) hide(child);
                    }
                }
                progress.update(data);
                progress.color = textColor(root, foreground(context), button);
            } else removeProgress();

            boolean showAction = data.getBoolean("dndEnabled") && action(data) != null
                    && System.currentTimeMillis() < data.getLong("end");
            if (!showAction) removeAction();
            else try {
                if (actionRow == null) {
                    actionRow = new LinearLayout(context);
                    actionRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    actionRow.setPadding(0, dp(context, 6), 0, 0);
                    button = nativeButton(context);
                    button.setOnClickListener(v -> sendAction());
                    actionRow.addView(button, new LinearLayout.LayoutParams(-2, dp(context, 40)));
                    sectionRoot.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));
                }
                button.setEnabled(true);
                button.setText(data.getBoolean("dndActive") ? "结束本次免打扰" : "开启免打扰");
                int color = textColor(root, foreground(context), button);
                button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                        new int[]{(color & 0xffffff) | 0x66000000, color}));
                if (button instanceof CardAction) ((CardAction) button).tint(color);
                button.setContentDescription(button.getText() + (data.getBoolean("otherDnd") ? "，系统免打扰已开启" : ""));
            } catch (Exception | LinkageError error) {
                removeAction();
                report(progress != null, progress != null ? "native_progress_only" : "native_action_unavailable");
                Logx.e(CourseAlarmEntry.TAG, "native course action layout", error);
                return;
            }
            report(progress != null || button != null, progress != null
                    ? (button != null ? "native_progress_and_action" : "native_progress_only")
                    : (button != null ? "native_action_only" : "native_controls_disabled"));
        }

        void hide(View view) {
            if (!hidden.containsKey(view)) hidden.put(view, view.getVisibility());
            view.setVisibility(View.GONE);
        }

        void sendAction() {
            if (data == null || button == null || !button.isShown()) return;
            PendingIntent action = action(data);
            if (action == null || System.currentTimeMillis() >= data.getLong("end")) return;
            button.setEnabled(false);
            try {
                Bundle options = null;
                if (action.isActivity() && Build.VERSION.SDK_INT >= 34) {
                    // SystemUI is the visible sender for this explicit touch. The permission
                    // page still presents the normal system authorization prompts.
                    options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                            Build.VERSION.SDK_INT >= 36
                                    ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                }
                action.send(app, 0, null, (pi, intent, code, text, extras) -> main.post(() -> {
                    main.removeCallbacks(refreshAfterAction);
                    main.postDelayed(refreshAfterAction, 300);
                }), main, null, options);
                main.removeCallbacks(refreshAfterAction); main.postDelayed(refreshAfterAction, 1500);
            } catch (Throwable error) {
                button.setEnabled(true); request();
            }
        }

        void report(boolean rendered, String reason) {
            String value = instance + ":" + rendered + ":" + reason;
            if (value.equals(reported)) return;
            reported = value;
            Bundle query = query(); query.putBoolean("rendered", rendered); query.putString("reason", reason);
            worker.post(() -> {
                try { app.getContentResolver().call(PROVIDER, CourseNativeCardBridge.CAPABILITY, null, query); }
                catch (Throwable ignored) { }
            });
        }

        void removeProgress() {
            if (progress != null) { progress.stop(); if (progress.getParent() instanceof ViewGroup) ((ViewGroup) progress.getParent()).removeView(progress); }
            progress = null;
            for (Map.Entry<View, Integer> entry : new ArrayList<>(hidden.entrySet())) {
                if (entry.getKey().getVisibility() == View.GONE) entry.getKey().setVisibility(entry.getValue());
            }
            hidden.clear();
        }
        void removeAction() {
            if (actionRow != null && actionRow.getParent() instanceof ViewGroup) ((ViewGroup) actionRow.getParent()).removeView(actionRow);
            actionRow = null; button = null;
        }
        void unmount() {
            main.removeCallbacks(mountLater); main.removeCallbacks(refreshAfterAction);
            removeProgress(); removeAction(); divider = null; nativeLine = null; sectionRoot = null;
        }
        void dispose() {
            disposed = true; generation++; unmount(); data = null;
            main.removeCallbacks(retryState);
            row.removeOnAttachStateChangeListener(this); sessions.remove(row);
        }
        @Override public void onViewAttachedToWindow(View view) { anchorAttempts = 0; stateAttempts = 0; request(); }
        @Override public void onViewDetachedFromWindow(View view) {
            generation++; again = false; unmount(); data = null;
            main.removeCallbacks(retryState);
        }
    }

    private static boolean valid(Bundle data, String instance) {
        if (data == null || !data.getBoolean("supported") || !instance.equals(data.getString("serviceInstanceId"))
                || text(data.getString("key")).isEmpty()) return false;
        long start = data.getLong("start"), end = data.getLong("end"), expires = data.getLong("expires");
        return start > 0 && end > start && expires >= end && System.currentTimeMillis() < expires;
    }

    private static PendingIntent action(Bundle data) {
        try {
            PendingIntent action = data.getParcelable("dndAction", PendingIntent.class);
            if (action == null || !action.isImmutable()) return null;
            String creator = action.getCreatorPackage();
            return CALENDAR.equals(creator) || "io.github.yuangug.coursealarm".equals(creator) ? action : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static TextView nativeButton(Context context) {
        int style = id(context, "attr", "couiTinyTranslateButtonColorStyle");
        TextView button = null;
        if (style != 0) try {
            button = (TextView) Class.forName("com.coui.appcompat.button.COUIButton", false, context.getClassLoader())
                    .getConstructor(Context.class, AttributeSet.class, int.class).newInstance(context, null, style);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { }
        if (button == null) button = new CardAction(context);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        return button;
    }

    private static final class CardAction extends android.widget.Button {
        private Integer tint;
        CardAction(Context context) { super(context); setStateListAnimator(null); }
        void tint(int color) {
            if (tint != null && tint == color) return;
            tint = color;
            int rgb = color & 0xffffff;
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(rgb | 0x16000000);
            surface.setCornerRadius(dp(getContext(), 20));
            setBackground(new RippleDrawable(ColorStateList.valueOf(rgb | 0x29000000), surface, null));
        }
    }

    private static final class ProgressLine extends View implements Runnable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect visibleRect = new Rect();
        final Session owner;
        long start, end;
        int color;
        boolean visible, scheduled;
        final android.view.ViewTreeObserver.OnPreDrawListener visibility = () -> {
            if (!scheduled) schedule();
            return true;
        };
        ProgressLine(Context context, Session owner) {
            super(context); this.owner = owner;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false); setFocusable(false);
        }
        void update(Bundle data) { start = data.getLong("start"); end = data.getLong("end"); invalidate(); schedule(); }
        void stop() { removeCallbacks(this); scheduled = false; }
        void schedule() {
            stop();
            if (visible && isAttachedToWindow() && isShown() && getGlobalVisibleRect(visibleRect) && System.currentTimeMillis() < end) {
                scheduled = true;
                postDelayed(this, 1000L - Math.floorMod(System.currentTimeMillis(), 1000L));
            }
        }
        @Override public void run() {
            scheduled = false;
            if (!visible || !isShown() || !getGlobalVisibleRect(visibleRect)) return;
            invalidate();
            if (System.currentTimeMillis() >= end) { owner.mountSafely(); return; }
            schedule();
        }
        @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); this.visible = visible; schedule(); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); visible = isShown();
            getViewTreeObserver().addOnPreDrawListener(visibility); schedule();
        }
        @Override protected void onDetachedFromWindow() {
            stop(); if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(visibility);
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int color = this.color;
            float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
            float height = Math.min(dp(getContext(), 4), getHeight() - getPaddingTop() - getPaddingBottom());
            if (right <= left || height <= 0) return;
            float top = (getHeight() - height + getPaddingTop() - getPaddingBottom()) * .5f;
            float radius = height * .5f;
            paint.setColor((color & 0xffffff) | 0x26000000);
            canvas.drawRoundRect(left, top, right, top + height, radius, radius, paint);
            float fraction = fraction(System.currentTimeMillis(), start, end);
            paint.setColor((color & 0xffffff) | 0xcc000000);
            float length = (right - left) * fraction;
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL)
                canvas.drawRoundRect(right - length, top, right, top + height, radius, radius, paint);
            else canvas.drawRoundRect(left, top, left + length, top + height, radius, radius, paint);
        }
    }

    static float fraction(long now, long start, long end) {
        return CourseNativeCardContract.fraction(now, start, end);
    }

    private static boolean vertical(View view) {
        return view instanceof LinearLayout && ((LinearLayout) view).getOrientation() == LinearLayout.VERTICAL;
    }

    private static View findNamed(View root, String name) {
        if (name.equals(resourceName(root))) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findNamed(((ViewGroup) root).getChildAt(i), name); if (found != null) return found;
        }
        return null;
    }
    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); } catch (RuntimeException ignored) { return ""; }
    }
    private static String describe(View view, int depth, int[] count) {
        if (depth > 6 || ++count[0] > 28) return "";
        StringBuilder out = new StringBuilder(view.getClass().getSimpleName()).append('#').append(resourceName(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            out.append('[').append(describe(((ViewGroup) view).getChildAt(i), depth + 1, count)).append(']');
        return out.toString();
    }
    private static int id(Context context, String type, String name) {
        int found = context.getResources().getIdentifier(name, type, "com.oplus.systemui.plugins");
        return found != 0 ? found : context.getResources().getIdentifier(name, type, context.getPackageName());
    }
    private static int dimension(Context context, String name, int fallback) {
        int id = id(context, "dimen", name); return id != 0 ? context.getResources().getDimensionPixelSize(id) : dp(context, fallback);
    }
    private static int foreground(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode & 0x30) == 0x20;
        return dark ? 0xfff5f5f5 : 0xff252525;
    }
    private static int textColor(View view, int fallback, View excluded) {
        if (view == excluded) return fallback;
        if (view instanceof TextView) return ((TextView) view).getCurrentTextColor();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            int color = textColor(((ViewGroup) view).getChildAt(i), 0, excluded);
            if (color != 0) return color;
        }
        return fallback;
    }
    private static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
