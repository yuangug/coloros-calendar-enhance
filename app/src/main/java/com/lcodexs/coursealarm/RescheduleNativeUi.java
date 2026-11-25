package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;

/** Host-owned COUI widgets and the calendar preference page container. No module theme resources. */
final class RescheduleNativeUi {
    final Activity activity;
    final Context context;
    final int pageTheme;
    final int foreground, secondary, background, surface, accent;
    final boolean dark;
    RescheduleNativeUi(Activity activity) {
        this.activity = activity;
        pageTheme = id("style", "AppNoTitleTheme.PreferenceFragment");
        if (pageTheme == 0) throw new IllegalStateException("宿主设置页主题不可用");
        context = new ContextThemeWrapper(activity, pageTheme);
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        foreground = attrColor("couiColorLabelPrimary", themeColor(android.R.attr.textColorPrimary, dark ? 0xffeeeeee : 0xff191919));
        secondary = attrColor("couiColorLabelSecondary", themeColor(android.R.attr.textColorSecondary, dark ? 0xffaaaaaa : 0xff767676));
        background = attrColor("couiColorBackgroundWithCard", dark ? 0xff000000 : 0xfff0f1f2);
        surface = attrColor("couiColorCardBackground", dark ? 0xff1e1e1e : 0xffffffff);
        accent = attrColor("couiColorPrimary", themeColor(android.R.attr.colorAccent, 0xff0080ff));
    }
    int id(String type, String name) { return activity.getResources().getIdentifier(name, type, activity.getPackageName()); }
    int dp(float n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    int dim(String name, int fallback) {
        int resource = id("dimen", name);
        return resource == 0 ? dp(fallback) : context.getResources().getDimensionPixelSize(resource);
    }
    int space(int n) { return dim("dp_" + n, n); }
    int themeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        try { return value.resourceId == 0 ? value.data : context.getColorStateList(value.resourceId).getDefaultColor(); }
        catch (Exception ignored) { return fallback; }
    }
    int attrColor(String attr, int fallback) {
        int id = id("attr", attr); return id == 0 ? fallback : themeColor(id, fallback);
    }
    View nativeView(String simpleName) throws Exception {
        return (View) Class.forName("com.coui.appcompat." + simpleName, false, activity.getClassLoader())
                .getConstructor(Context.class).newInstance(context);
    }
    static Object call(Object target, String name, Object... args) throws Exception {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] types = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < types.length; i++) {
                    Class<?> type = types[i]; Object value = args[i];
                    if (value == null) { if (type.isPrimitive()) matches = false; }
                    else if (type.isPrimitive()) {
                        if (!(type == int.class && value instanceof Integer || type == long.class && value instanceof Long
                                || type == boolean.class && value instanceof Boolean || type == float.class && value instanceof Float)) matches = false;
                    } else if (!type.isInstance(value)) matches = false;
                }
                if (matches) { method.setAccessible(true); return method.invoke(target, args); }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }
    LinearLayout column() {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    LinearLayout line() {
        LinearLayout view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    TextView text(String value, int sp, boolean quiet) {
        TextView view = new TextView(context); view.setText(value);
        int appearance = id("style", quiet ? "COUIPreferenceSummaryStyle" : "COUIPreferenceTitleStyle");
        if (appearance != 0) view.setTextAppearance(appearance);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim("sp_" + sp, sp));
        view.setTextColor(quiet ? secondary : foreground); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    TextView action(String value, Runnable click) throws Exception {
        TextView button = action(value, click, "couiTinyTranslateButtonColorStyle");
        // The tiny translucent style supplies a white foreground in the host's
        // preference theme. These inline actions sit on a pale page background.
        // Keep its native material/press behavior and select readable theme colors.
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{attrColor("couiColorDisabledNeutral", secondary), accent}));
        return button;
    }
    private TextView action(String value, Runnable click, String styleAttr) throws Exception {
        int style = id("attr", styleAttr);
        if (style == 0) throw new IllegalStateException("宿主按钮样式不可用");
        // COUIButton(Context) uses AppCompat's buttonStyle, which produces a dark
        // rectangular background in this Activity. Use the host's COUI style attribute
        // so rounding, touch animation and disabled/night colors all stay native.
        TextView button = (TextView) Class.forName("com.coui.appcompat.button.COUIButton", false, activity.getClassLoader())
                .getConstructor(Context.class, android.util.AttributeSet.class, int.class)
                .newInstance(context, null, style);
        button.setText(value); button.setAllCaps(false);
        button.setMinimumHeight(dp(44)); button.setMinHeight(dp(44)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setOnClickListener(v -> click.run()); return button;
    }
    int courseColor(int index) throws Exception {
        Class<?> palette = Class.forName("com.oplus.todotaskability.TodoColor", false, activity.getClassLoader());
        Object token = palette.getMethod("createByIndex", Integer.class).invoke(null, index);
        int resource = ((Number) call(token, "getColor")).intValue();
        return context.getColor(resource);
    }
    LinearLayout card() {
        LinearLayout card = column();
        int resource = id("drawable", "bg_shape_white_round_20");
        if (resource == 0) throw new IllegalStateException("宿主卡片样式不可用");
        card.setBackground(context.getDrawable(resource)); card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(space(16), space(8), space(16), space(8)); card.setLayoutParams(lp); return card;
    }
    void heading(LinearLayout parent, String title) {
        TextView view = text(title, 14, true); view.setPadding(space(32), space(16), space(32), space(4)); parent.addView(view);
    }
    Row row(String title, String value, Runnable click) throws Exception {
        int resource = id("layout", "coui_preference");
        if (resource == 0) throw new IllegalStateException("宿主设置项布局不可用");
        View root = LayoutInflater.from(context).inflate(resource, null, false);
        root.setPaddingRelative(space(16), root.getPaddingTop(), space(16), root.getPaddingBottom());
        TextView label = root.findViewById(android.R.id.title);
        if (label == null) throw new IllegalStateException("宿主设置项标题不可用");
        label.setText(title); label.setTextColor(foreground);
        View summary = root.findViewById(android.R.id.summary);
        if (summary != null) summary.setVisibility(View.GONE);
        View icon = root.findViewById(android.R.id.icon);
        if (icon != null && icon.getParent() instanceof View) ((View) icon.getParent()).setVisibility(View.GONE);
        int assignmentId = id("id", "assignment");
        View assignment = assignmentId == 0 ? null : root.findViewById(assignmentId);
        if (assignment != null) assignment.setVisibility(View.GONE);
        ViewGroup widget = root.findViewById(android.R.id.widget_frame);
        if (widget == null) throw new IllegalStateException("宿主设置项内容区不可用");
        widget.removeAllViews(); widget.setVisibility(View.VISIBLE);
        TextView right = text(value, 14, true); right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        right.setMaxWidth(dp(210)); right.setPadding(dp(12), dp(8), 0, dp(8));
        widget.addView(right, new ViewGroup.LayoutParams(-2, -2));
        root.setMinimumHeight(dim("support_preference_min_height", 48)); root.setEnabled(true); root.setFocusable(click != null);
        if (click != null) root.setOnClickListener(v -> click.run());
        return new Row(root, right);
    }
    static final class Row {
        final View view; final TextView value;
        Row(View view, TextView value) { this.view = view; this.value = value; }
        void value(String text) { value.setText(text); }
    }
    View check(boolean checked) throws Exception {
        View checkbox = nativeView("checkbox.COUICheckBox"); call(checkbox, "setChecked", checked);
        checkbox.setClickable(false); checkbox.setFocusable(false); return checkbox;
    }
    void checked(View view, boolean checked) { try { call(view, "setChecked", checked); } catch (Exception e) { throw new IllegalStateException(e); } }
    View wheel(String[] items, int selected) throws Exception {
        View wheel = nativeView("picker.COUINumberPicker");
        call(wheel, "setMinValue", 0); call(wheel, "setMaxValue", items.length - 1);
        call(wheel, "setDisplayedValues", (Object) items); call(wheel, "setValue", selected);
        call(wheel, "setWrapSelectorWheel", false); return wheel;
    }
    int wheelValue(View wheel) throws Exception { return ((Number) call(wheel, "getValue")).intValue(); }
    Dialog sheet(String title, LinearLayout content, String save, Runnable confirm, Runnable dismissed) throws Exception {
        return sheet(title, content, save, confirm, dismissed, false);
    }
    Dialog sheet(String title, LinearLayout content, String save, Runnable confirm, Runnable dismissed, boolean material) throws Exception {
        Class<?> cls = Class.forName("com.coui.appcompat.panel.COUIBottomSheetDialog", false, activity.getClassLoader());
        int style = id("style", "COUIFitContentBottomSheetDialog");
        Dialog dialog = (Dialog) cls.getConstructor(Context.class, int.class).newInstance(context, style);
        LinearLayout root = column();
        if (!material) root.setBackgroundColor(surface);
        else {
            // The panel owns its rounded geometry, shadow, blur lifecycle and IME
            // handling. A bottom-bar material on the content produces an ellipse.
            call(dialog, "setBackgroundBlur", true, false);
            root.setPadding(0, 0, 0, space(16));
        }
        View header = nativeView("toolbar.COUIToolbar");
        if (material) header.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        call(header, "setTitle", title); call(header, "setTitleTextAppearance", context, id("style", "textAppearanceSecondTitle"));
        int icon = id("drawable", "icon_cloose");
        if (icon == 0) throw new IllegalStateException("宿主关闭图标不可用");
        Menu menu = (Menu) call(header, "getMenu");
        MenuItem close = menu.add("关闭").setIcon(icon); close.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        close.setOnMenuItemClickListener(item -> { dialog.dismiss(); return true; });
        root.addView(header, new LinearLayout.LayoutParams(-1, dim("toolbar_height", 52))); root.addView(content);
        if (save != null) {
            TextView button = action(save, confirm, "couiButtonColorfulWhiteStyle");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dim("coui_dialog_button_height", 44)); lp.setMargins(space(24), space(8), space(24), space(20));
            root.addView(button, lp);
        }
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(true); dialog.setOnDismissListener(d -> dismissed.run());
        if (material && dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        Object behavior = call(dialog, "getBehavior");
        try { call(behavior, "setDraggable", false); } catch (Exception ignored) { }
        Object chrome = call(dialog, "getDragableLinearLayout");
        if (chrome != null) for (String part : new String[]{"getDragView", "getPanelBarView", "getDrawLayout"}) {
            Object view = call(chrome, part); if (view instanceof View) ((View) view).setVisibility(View.GONE);
        }
        try {
            dialog.show();
            if (material && Build.VERSION.SDK_INT >= 30) {
                // Account only for the system bar area which actually intersects
                // this panel; COUI already supplies a margin on floating panels.
                root.setOnApplyWindowInsetsListener((v, insets) -> {
                    v.post(() -> {
                        if (!v.isAttachedToWindow()) return;
                        int[] location = new int[2]; v.getLocationOnScreen(location);
                        android.graphics.Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                        int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                        int covered = Math.max(0, location[1] + v.getHeight() - (bounds.bottom - nav));
                        int bottom = space(16) + Math.min(nav, covered);
                        if (v.getPaddingBottom() != bottom) v.setPadding(0, 0, 0, bottom);
                    });
                    return insets;
                });
                root.requestApplyInsets();
            }
            return dialog;
        }
        catch (Exception failure) { closeFailed(dialog); throw failure; }
    }
    static final class Page {
        final Dialog dialog; final LinearLayout body; final MenuItem save;
        Page(Dialog dialog, LinearLayout body, MenuItem save) { this.dialog = dialog; this.body = body; this.save = save; }
    }
    Page page(String title, Runnable confirmed, Runnable dismissed) throws Exception {
        return page(title, confirmed, dismissed, false);
    }
    Page page(String title, Runnable confirmed, Runnable dismissed, boolean material) throws Exception {
        // A normal Dialog theme is floating even at MATCH_PARENT and leaves the timetable
        // visible behind the status/navigation bars. Reuse the actual preference Activity theme.
        Dialog dialog = new Dialog(activity, pageTheme); dialog.setOwnerActivity(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int layout = id("layout", "activity_preference");
        if (layout == 0) throw new IllegalStateException("宿主页面容器不可用");
        ViewGroup hostRoot = (ViewGroup) LayoutInflater.from(context).inflate(layout, null, false);
        hostRoot.setFitsSystemWindows(false); hostRoot.setBackgroundColor(background);
        LinearLayout root = column(); hostRoot.addView(root, new ViewGroup.LayoutParams(-1, -1));
        View toolbar = nativeView("toolbar.COUIToolbar"); toolbar.setBackgroundColor(background);
        call(toolbar, "setTitle", title); call(toolbar, "setTitleTextColor", foreground);
        call(toolbar, "setTitleTextAppearance", context, id("style", "textAppearanceSecondTitle"));
        int back = id("drawable", "coui_back_arrow");
        if (back == 0) throw new IllegalStateException("宿主返回图标不可用");
        call(toolbar, "setNavigationIcon", back); call(toolbar, "setNavigationContentDescription", "返回");
        call(toolbar, "setNavigationOnClickListener", (View.OnClickListener) v -> dialog.dismiss());
        Menu menu = (Menu) call(toolbar, "getMenu"); MenuItem save = menu.add("确认");
        save.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        save.setOnMenuItemClickListener(item -> { confirmed.run(); return true; });
        // COUIToolbar already renders material on its action/navigation controls.
        // Wrapping the entire toolbar in a bottom-bar material creates a large pill.
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dim("toolbar_height", 52)));
        LinearLayout body = column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(hostRoot); dialog.setOnDismissListener(d -> dismissed.run());
        Window window = dialog.getWindow();
        if (window == null) throw new IllegalStateException("调课页面窗口不可用");
        window.setBackgroundDrawable(new ColorDrawable(background)); window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            hostRoot.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int bottom = material ? Math.max(safe.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom) : safe.bottom;
                v.setPadding(safe.left, safe.top, safe.right, bottom); return insets;
            });
        } else hostRoot.setFitsSystemWindows(true);
        try {
            dialog.show(); window.setLayout(-1, -1); window.setStatusBarColor(background); window.setNavigationBarColor(background);
            if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                window.getInsetsController().setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
            hostRoot.requestApplyInsets(); return new Page(dialog, body, save);
        } catch (Exception failure) { closeFailed(dialog); throw failure; }
    }
    Dialog error(String title, String message, Runnable dismissed) throws Exception {
        Object builder = Class.forName("com.coui.appcompat.dialog.COUIAlertDialogBuilder", false, activity.getClassLoader())
                .getConstructor(Context.class).newInstance(context);
        call(builder, "setTitle", title); call(builder, "setMessage", message);
        call(builder, "setPositiveButton", "确定", (DialogInterface.OnClickListener) (d, which) -> { });
        Dialog dialog = (Dialog) call(builder, "create"); dialog.setOnDismissListener(d -> dismissed.run());
        try { dialog.show(); return dialog; }
        catch (Exception failure) { closeFailed(dialog); throw failure; }
    }
    private static void closeFailed(Dialog dialog) {
        // The caller cannot track a window until this method returns. A failure after
        // show() must remove that window here, without firing an unregistered callback.
        try { dialog.setOnDismissListener(null); dialog.dismiss(); }
        catch (Exception ignored) { }
    }
}
