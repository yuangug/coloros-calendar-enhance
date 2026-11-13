package com.lcodexs.coursealarm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

/**
 * 教务系统导入（网页登录版）：
 * 弹一个全屏 WebView，用户自己输入网址 → 自己登录 → 自己点到「学期理论课表」→ 点确定，
 * 我们抓当前页面（含同源 iframe）的 outerHTML，交给 JwClient 的表格网格解析器。
 *
 * 为什么这么做：教务系统几乎都是「登录态 + JS 渲染 + 可能 iframe」，直连 POST 只对少数学校有效；
 * 借用户已经登录好的 WebView 拿 DOM 是最省事也最稳的路子（WakeUp 的每校适配器本质上也是在解这个页面）。
 *
 * 模块不能新增 Activity（LSPosed 模块没法往宿主 manifest 加组件），所以这里用一个全屏 Dialog 承载 WebView。
 */
final class JwWebView {
    private static final String T = CourseAlarmEntry.TAG;

    interface OnHtml {
        void onHtml(String html, String title);
    }

    private JwWebView() {
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void open(Activity a, OnHtml cb) {
        if (a == null) return;
        boolean night = (a.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF141416 : 0xFFFFFFFF;
        int fg = night ? 0xFFEDEDED : 0xFF1A1A1A;
        int hint = night ? 0xFF8A8A8E : 0xFF8C8C92;
        int field = night ? 0xFF2A2A2D : 0xFFF2F2F5;
        int divider = night ? 0xFF333336 : 0xFFE6E6E9;
        int accent = night ? 0xFF7BB0FF : 0xFF1464F4;

        final Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        // 全屏 Dialog 不吃 insets，自己补状态栏/导航栏高度，否则地址栏会被状态栏压住
        int sbH = res(a, "status_bar_height");
        int nbH = res(a, "navigation_bar_height");
        root.setPadding(0, sbH > 0 ? sbH : PickerSheet.dp(a, 28), 0,
                nbH > 0 ? nbH : PickerSheet.dp(a, 16));

        // ── 顶部：地址栏 + 打开 ───────────────────────────────────────────────
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(PickerSheet.dp(a, 12), PickerSheet.dp(a, 10),
                PickerSheet.dp(a, 8), PickerSheet.dp(a, 10));

        final EditText url = new EditText(a);
        url.setSingleLine(true);
        url.setHint("输入你学校的教务系统网址（登录页）");
        url.setHintTextColor(hint);
        url.setTextColor(fg);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setPadding(PickerSheet.dp(a, 12), PickerSheet.dp(a, 8),
                PickerSheet.dp(a, 12), PickerSheet.dp(a, 8));
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(field);
        fbg.setCornerRadius(PickerSheet.dp(a, 10));
        url.setBackground(fbg);
        String last = Prefs.jwLastUrl();
        url.setText(last == null || last.isEmpty() ? "http://" : last);
        bar.addView(url, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(a);
        go.setText("打开");
        go.setTextColor(accent);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        go.setTypeface(go.getTypeface(), android.graphics.Typeface.BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(PickerSheet.dp(a, 14), PickerSheet.dp(a, 8),
                PickerSheet.dp(a, 6), PickerSheet.dp(a, 8));
        bar.addView(go);
        root.addView(bar);

        View hline = new View(a);
        hline.setBackgroundColor(divider);
        root.addView(hline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // ── 中间：WebView ────────────────────────────────────────────────────
        final WebView wv = new WebView(a);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " LSPX-CourseAlarm");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        // 有的 ROM 上 WebView 放进自定义 Dialog 会整片空白（硬件层没合成）——强制软件层最稳
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                view.loadUrl(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                Logx.i(T, "jw webview page finished url=" + u
                        + " height=" + view.getContentHeight()
                        + " progress=" + view.getProgress());
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                Logx.w(T, "jw webview error " + (req == null ? "" : req.getUrl())
                        + " code=" + (err == null ? -1 : err.getErrorCode())
                        + " desc=" + (err == null ? "" : err.getDescription()));
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // 很多教务系统的证书是自签/过期；用户是主动打开自己学校的页面，这里放行并记日志
                Logx.w(T, "jw webview ssl error, proceed: "
                        + (error == null ? "" : error.getUrl()));
                handler.proceed();
            }
        });
        wv.setWebChromeClient(new WebChromeClient());
        root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        // ── 底部：取消 / 确定 ────────────────────────────────────────────────
        View hline2 = new View(a);
        hline2.setBackgroundColor(divider);
        root.addView(hline2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = new TextView(a);
        cancel.setText("取消");
        cancel.setTextColor(accent);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setGravity(Gravity.CENTER);
        cancel.setMinHeight(PickerSheet.dp(a, 52));
        cancel.setOnClickListener(v -> d.dismiss());
        bottom.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View vline = new View(a);
        vline.setBackgroundColor(divider);
        bottom.addView(vline, new LinearLayout.LayoutParams(1, PickerSheet.dp(a, 26)));

        TextView ok = new TextView(a);
        ok.setText("确定（解析当前页课表）");
        ok.setTextColor(accent);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ok.setTypeface(ok.getTypeface(), android.graphics.Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setMinHeight(PickerSheet.dp(a, 52));
        bottom.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        root.addView(bottom);

        go.setOnClickListener(v -> {
            String u = normalize(url.getText().toString());
            if (u.isEmpty()) {
                CurriculumIo.toast(a, "先填网址");
                return;
            }
            Prefs.setJwLastUrl(u);
            Logx.i(T, "jw webview load " + u);
            wv.loadUrl(u);
        });

        ok.setOnClickListener(v -> {
            String cur = wv.getUrl();
            Logx.i(T, "jw webview harvest, current url=" + cur);
            String js = "(function(){var out=[];try{out.push(document.documentElement.outerHTML);}catch(e){}"
                    + "try{var fs=document.querySelectorAll('iframe,frame');"
                    + "for(var i=0;i<fs.length;i++){try{var doc=fs[i].contentDocument;"
                    + "if(doc&&doc.documentElement){out.push(doc.documentElement.outerHTML);}}catch(e){}}}catch(e){}"
                    + "return out.join('\\n<!--frame-->\\n');})()";
            wv.evaluateJavascript(js, value -> {
                String html = unquote(value);
                int n = html == null ? 0 : html.length();
                Logx.i(T, "jw webview harvested html len=" + n);
                if (n < 200) {
                    CurriculumIo.toast(a, "页面还没加载好，或当前页不是课表");
                    return;
                }
                d.dismiss();
                if (cb != null) cb.onHtml(html, cur);
            });
        });

        d.setOnDismissListener(x -> {
            try {
                root.removeView(wv);
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.destroy();
            } catch (Throwable ignored) {
            }
        });

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        try {
            d.show();
        } catch (Throwable t) {
            Logx.e(T, "jw webview show failed", t);
            CurriculumIo.toast(a, "打开网页失败：" + t);
        }
    }

    private static int res(Context c, String name) {
        try {
            int id = c.getResources().getIdentifier(name, "dimen", "android");
            return id == 0 ? 0 : c.getResources().getDimensionPixelSize(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String normalize(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty() || "http://".equals(u)) return "";
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:")) return u;
        u = "http://" + u;
        return u;
    }

    /** evaluateJavascript 回传的是 JSON 字符串字面量，解成真字符串 */
    private static String unquote(String v) {
        if (v == null) return null;
        if ("null".equals(v)) return null;
        try {
            if (v.startsWith("\"")) {
                return new JSONArray("[" + v + "]").getString(0);
            }
            return v;
        } catch (Throwable t) {
            return v;
        }
    }

    static void toast(Context c, String m) {
        CurriculumIo.toast(c, m);
    }
}
