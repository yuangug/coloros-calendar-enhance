package com.lcodexs.coursealarm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;

/** Both school import methods share the calendar's preference page and navigation controls. */
final class JwWebView {
    interface OnHtml { void onHtml(String html, String title); }
    final Activity activity;
    final OnHtml html;
    final ShiguangImport.Source source;
    final Runnable finishSelection;
    RescheduleNativeUi.Page page;
    WebView web;
    private EditText address;
    private TextView status;
    private TextView emptyMessage;
    private View emptyState;
    private FrameLayout webContainer;
    private ShiguangSession session;
    private Dialog jsDialog;
    private int document;
    private boolean ready, tried, closed;

    static void open(Activity a, OnHtml callback) {
        new JwWebView(a, callback, null, () -> {}).show();
    }
    static void open(Activity a, ShiguangImport.Source source, Runnable finishSelection) {
        new JwWebView(a, null, source, finishSelection).show();
    }
    private JwWebView(Activity a, OnHtml html, ShiguangImport.Source source, Runnable finishSelection) {
        activity = a; this.html = html; this.source = source; this.finishSelection = finishSelection;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void show() {
        if (!TransferUi.alive(activity)) return;
        try {
            RescheduleNativeUi ui = new RescheduleNativeUi(activity);
            page = ui.page(source == null ? "教务系统导入" : "拾光课表", this::parse, this::dispose, true);
            page.save.setTitle("解析课表").setEnabled(false);
            View addressBar = ui.nativeView("searchview.COUISearchBar");
            RescheduleNativeUi.call(addressBar, "setSearchAnimateType", 1);
            RescheduleNativeUi.call(addressBar, "setInputMethodAnimationEnabled", false);
            RescheduleNativeUi.call(addressBar, "setFunctionalButtonText", "打开");
            address = (EditText) RescheduleNativeUi.call(addressBar, "getSearchEditText");
            address.setHint("教务系统网址");
            address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            address.setImeOptions(EditorInfo.IME_ACTION_GO);
            address.setSelectAllOnFocus(true);
            address.setSingleLine(true);
            address.setText(source == null ? Prefs.jwLastUrl() : source.adapter.url);
            int searchIcon = ui.id("drawable", "menu_ic_search");
            if (searchIcon != 0)
                RescheduleNativeUi.call(addressBar, "setSearchViewIcon", ui.context.getDrawable(searchIcon));
            TextView open = (TextView) RescheduleNativeUi.call(addressBar, "getFunctionalButton");
            if (open != null) open.setOnClickListener(v -> go());
            addressBar.setOnClickListener(v -> {
                address.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(address, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(-1, ui.dim("coui_search_view_height", 52));
            addressLp.setMargins(ui.space(16), ui.space(8), ui.space(16), ui.space(8));
            page.body.addView(addressBar, addressLp);
            addressBar.post(() -> {
                if (closed || !addressBar.isAttachedToWindow()) return;
                try {
                    if (((Number) RescheduleNativeUi.call(addressBar, "getSearchState")).intValue() != 1)
                        RescheduleNativeUi.call(addressBar, "changeStateImmediately", 1);
                } catch (Exception e) { TransferUi.failed(activity, e); }
            });
            status = TransferUi.caption(ui, source == null ? "" : source.adapter.name);
            if (source == null) status.setVisibility(View.GONE);
            page.body.addView(status);
            web = new WebView(activity);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setLoadWithOverviewMode(true); settings.setUseWideViewPort(true);
            settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    return origin(request.getUrl().toString()) == null;
                }
                @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                    if (closed) return;
                    hidePlaceholder();
                    ++document; ready = false; tried = false; stopSession();
                    page.save.setTitle("解析课表").setEnabled(false);
                    Uri site = origin(url);
                    if (site != null) address.setText(site.toString());
                }
                @Override public void onPageFinished(WebView v, String url) {
                    if (closed || !url.equals(v.getUrl())) return;
                    ready = origin(url) != null; page.save.setEnabled(ready);
                    if (ready) hidePlaceholder();
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (!closed && request.isForMainFrame()) {
                        String detail = "网页加载失败：" + error.getDescription();
                        message(detail); showPlaceholder(detail);
                    }
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, android.net.http.SslError error) {
                    handler.cancel(); if (!closed) {
                        message("无法验证该教务网站的证书");
                        showPlaceholder("无法验证该教务网站的证书，请检查网址或联系学校管理员。");
                    }
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult result) {
                    return webDialog(msg, null, result, false);
                }
                @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult result) {
                    return webDialog(msg, null, result, true);
                }
                @Override public boolean onJsPrompt(WebView v, String url, String msg, String value, JsPromptResult result) {
                    return webDialog(msg, value == null ? "" : value, result, true);
                }
            });
            web.setBackgroundColor(ui.background);
            webContainer = new FrameLayout(ui.context);
            webContainer.setBackgroundColor(ui.background);
            webContainer.addView(web, new FrameLayout.LayoutParams(-1, -1));
            emptyState = ui.column();
            ((LinearLayout) emptyState).setGravity(Gravity.CENTER);
            emptyState.setPadding(ui.space(36), ui.space(24), ui.space(36), ui.space(24));
            TextView emptyTitle = ui.text("打开教务系统", 18, false);
            emptyTitle.setGravity(Gravity.CENTER);
            emptyMessage = ui.text("输入教务系统网址并点击“打开”。登录后进入个人课表页面，再点右上角“解析课表”。", 14, true);
            emptyMessage.setGravity(Gravity.CENTER);
            emptyMessage.setMaxLines(4);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.setMargins(0, 0, 0, ui.space(8));
            ((LinearLayout) emptyState).addView(emptyTitle, titleLp);
            ((LinearLayout) emptyState).addView(emptyMessage, new LinearLayout.LayoutParams(-1, -2));
            webContainer.addView(emptyState, new FrameLayout.LayoutParams(-1, -1));
            page.body.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
            if (address.getText().toString().trim().isEmpty())
                showPlaceholder("输入教务系统网址并点击“打开”。登录后进入个人课表页面，再点右上角“解析课表”。");
            address.setOnEditorActionListener((v, id, e) -> {
                if (id != EditorInfo.IME_ACTION_GO) return false;
                go(); return true;
            });
            page.body.setFocusableInTouchMode(true); page.body.requestFocus();
            if (source != null && !source.adapter.url.isEmpty()) web.loadUrl(source.adapter.url);
        } catch (Exception e) { close(); TransferUi.failed(activity, e); }
    }
    private boolean webDialog(String message, String value, JsResult result, boolean cancelable) {
        if (closed || jsDialog != null && jsDialog.isShowing()) { result.cancel(); return true; }
        try {
            if (value == null) {
                jsDialog = TransferUi.alert(activity, "教务系统", message, "确定", yes -> {
                    if (yes || !cancelable) result.confirm(); else result.cancel();
                });
            } else {
                jsDialog = TransferUi.form(activity, "教务系统", Collections.singletonList(new String[]{"value", message}),
                        Collections.singletonMap("value", value), answer -> {
                            if (answer == null) result.cancel();
                            else ((JsPromptResult) result).confirm(answer.get("value"));
                        });
            }
        } catch (Exception e) { result.cancel(); TransferUi.failed(activity, e); }
        return true;
    }
    private void go() {
        String url = address.getText().toString().trim();
        if (!url.contains(":") || !url.contains("://") && url.matches("[\\w.-]+:[0-9]+.*")) url = "https://" + url;
        if (origin(url) == null) {
            message("请输入教务系统的 http 或 https 网址");
            showPlaceholder("请输入有效的教务系统 http 或 https 网址。"); return;
        }
        if (source == null) Prefs.setJwLastUrl(origin(url).toString());
        web.requestFocus(); web.loadUrl(url);
    }
    static Uri origin(String url) {
        if (url == null) return null;
        Uri u = Uri.parse(url);
        if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))
                || u.getHost() == null || u.getHost().isEmpty() || u.getUserInfo() != null) return null;
        return new Uri.Builder().scheme(u.getScheme().toLowerCase(java.util.Locale.ROOT)).encodedAuthority(u.getEncodedAuthority()).build();
    }
    private void parse() {
        if (closed || !ready || web == null || origin(web.getUrl()) == null) return;
        if (source != null) {
            if (tried) { stopSession(); web.reload(); message("网页正在重新加载，加载后可再次解析"); return; }
            tried = true; page.save.setTitle("重新加载");
            message("正在解析：" + source.adapter.name);
            session = new ShiguangSession(this);
            session.start(origin(web.getUrl()));
            return;
        }
        final int epoch = document;
        page.save.setEnabled(false);
        String site = origin(web.getUrl()).toString();
        web.evaluateJavascript("(function(){const out=[document.documentElement.outerHTML];"
                + "for(const f of document.querySelectorAll('iframe,frame'))try{if(f.contentDocument)"
                + "out.push(f.contentDocument.documentElement.outerHTML)}catch(e){};return out.join('\\n<!--frame-->\\n');})()", raw -> {
            if (closed || document != epoch) return;
            page.save.setEnabled(true);
            try {
                String data = new JSONArray("[" + raw + "]").getString(0);
                if (data.length() < 200) { message("页面还没加载好，或当前页不是课表"); return; }
                close(); html.onHtml(data, site);
            } catch (Exception e) { message("读不到当前页的课表"); }
        });
    }
    void message(String message) {
        if (closed || status == null) return;
        status.setVisibility(View.VISIBLE); status.setText(message);
    }
    private void showPlaceholder(String message) {
        if (closed || emptyState == null || emptyMessage == null) return;
        emptyMessage.setText(message);
        emptyState.setVisibility(View.VISIBLE);
        if (web != null) web.setVisibility(View.INVISIBLE);
    }
    private void hidePlaceholder() {
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (web != null) web.setVisibility(View.VISIBLE);
    }
    boolean active(ShiguangSession s) { return !closed && session == s && TransferUi.alive(activity); }
    void close() { if (page != null && page.dialog.isShowing()) page.dialog.dismiss(); dispose(); }
    private void stopSession() { ShiguangSession old = session; session = null; if (old != null) old.close(); }
    private void dispose() {
        if (closed) return;
        closed = true; stopSession();
        if (jsDialog != null) jsDialog.dismiss();
        if (web != null) {
            if (web.getParent() instanceof ViewGroup) ((ViewGroup)web.getParent()).removeView(web);
            web.stopLoading(); web.setWebChromeClient(null); web.setWebViewClient(new WebViewClient()); web.destroy(); web = null;
        }
    }
}
