package com.lcodexs.coursealarm;

import android.app.Dialog;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One document, one user-initiated parse, one native preview. Navigation revokes the port. */
final class ShiguangSession {
    private final JwWebView screen;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail("解析未完成。请确认已登录并打开课表页，再重新解析。");
    private WebMessagePort port;
    private Dialog prompt;
    private Uri origin;
    private boolean started, completed, closed;

    ShiguangSession(JwWebView screen) { this.screen = screen; }
    void start(Uri site) {
        if (screen.source.adapter.localXit && !"https://xgjwxt.xit.edu.cn".equals(site.toString())) {
            fail("请先登录并返回厦门工学院教务系统的课表页"); return;
        }
        origin = site;
        String token = UUID.randomUUID().toString();
        arm();
        screen.web.evaluateJavascript(screen.source.bridge + "(" + JSONObject.quote(token) + ")", ok -> {
            if (!live()) return;
            if (!"true".equals(ok)) { fail("无法在此网页启动拾光适配器"); return; }
            WebMessagePort[] channel = null;
            try {
                channel = screen.web.createWebMessageChannel();
                port = channel[0];
                port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort sender, WebMessage message) {
                        if (live() && !completed) receive(message.getData());
                    }
                }, ui);
                screen.web.postWebMessage(new WebMessage(token, new WebMessagePort[]{channel[1]}), site);
            } catch (RuntimeException e) {
                if (channel != null) try { channel[1].close(); } catch (RuntimeException ignored) {}
                fail("当前网页无法连接拾光适配器");
            }
        });
    }
    private boolean live() {
        return !closed && screen.active(this) && origin.equals(JwWebView.origin(screen.web.getUrl()));
    }
    private void arm() { ui.removeCallbacks(timeout); ui.postDelayed(timeout, 120000); }
    private void receive(String raw) {
        try {
            if (raw == null || raw.length() > 420000) throw new IllegalArgumentException("拾光返回的数据过大或为空");
            JSONObject msg = new JSONObject(raw);
            String type = msg.getString("type");
            if (screen.source.adapter.localXit) {
                if ("error".equals(type)) { fail(msg.getString("message")); return; }
                if (!"xit".equals(type)) throw new IllegalArgumentException("学校课表响应无效");
                completed = true; ui.removeCallbacks(timeout);
                finishXit(msg); return;
            }
            if ("ready".equals(type)) {
                if (!started) {
                    started = true;
                    // Keep classic-script global scope: upstream prompts resolve named validator functions here.
                    screen.web.evaluateJavascript(screen.source.script, null);
                }
                return;
            }
            if (!started) return;
            if ("error".equals(type)) { fail(msg.getString("message")); return; }
            if ("status".equals(type)) { screen.message(msg.getString("message")); return; }
            if ("done".equals(type)) {
                completed = true; ui.removeCallbacks(timeout);
                finish(msg.getJSONArray("courses"), msg.optBoolean("slots") || msg.optBoolean("config") || msg.optBoolean("combo"));
                return;
            }
            if (prompt != null && prompt.isShowing()) throw new IllegalArgumentException("拾光适配器同时打开了多个选择框");
            int id = msg.getInt("id");
            String title = msg.getString("title");
            ui.removeCallbacks(timeout); // time spent choosing an academic term is not a network timeout
            switch (type) {
                case "choose": {
                    JSONArray choices = msg.getJSONArray("choices");
                    if (choices.length() > 500 || choices.length() == 0) throw new IllegalArgumentException("教务选项列表无效");
                    List<String> labels = new ArrayList<>();
                    for (int i = 0; i < choices.length(); i++) labels.add(choices.getString(i));
                    prompt = TransferUi.choose(screen.activity, title, labels, msg.optInt("selected", -1), val -> reply(id, val));
                    break;
                }
                case "alert":
                    prompt = TransferUi.alert(screen.activity, title, msg.getString("message"),
                            msg.optString("confirm", "确定"), val -> reply(id, val));
                    break;
                case "prompt":
                    prompt = TransferUi.form(screen.activity, title,
                            Collections.singletonList(new String[]{"value", msg.getString("tip")}),
                            Collections.singletonMap("value", msg.optString("value", "")),
                            val -> reply(id, val == null ? null : val.get("value")));
                    break;
                default: throw new IllegalArgumentException("拾光适配器使用了不支持的交互");
            }
        } catch (Exception e) { fail(e.getMessage()); }
    }
    private void reply(int id, Object value) {
        if (!live() || completed) return;
        try {
            port.postMessage(new WebMessage(new JSONObject().put("id", id)
                    .put("value", value == null ? JSONObject.NULL : value).toString()));
            arm();
        } catch (Exception e) { fail("无法将选择返回教务网页"); }
    }
    private void finish(JSONArray rows, boolean hasConfig) {
        screen.message("正在核对目标课表…");
        new Thread(() -> {
            try {
                TableCtx frozen = TableCtx.loadForAlarm(screen.activity);
                TableCtx timing = TableCtx.fromMetadata(frozen.calendarId, frozen.tableName, frozen.tableJson);
                List<Course> courses = ShiguangCourses.parse(rows, timing.weekCount, timing.periodStart, timing.periodEnd);
                ui.post(() -> {
                    if (!live()) return;
                    String source = screen.source.adapter.name;
                    screen.close(); screen.finishSelection.run();
                    CurriculumIo.preview(screen.activity, courses, "拾光 · " + source, frozen, hasConfig
                            ? "仅导入课程，沿用目标课表的学期和节次时间。" : "");
                });
            } catch (Exception e) { ui.post(() -> { if (live()) fail(e.getMessage()); }); }
        }, "course-shiguang-preview").start();
    }
    private void finishXit(JSONObject data) {
        new Thread(() -> {
            try {
                JwParser.ParseResult parsed = JwParser.parse(data.getString("table"));
                if (parsed.courses.isEmpty()) throw new IllegalArgumentException("没有解析到课程，请打开学期理论课表");
                TableCtx frozen = TableCtx.loadForAlarm(screen.activity);
                TableCtx target = TableCtx.fromMetadata(frozen.calendarId, frozen.tableName, frozen.tableJson);
                XitTiming draft = new XitTiming(data, target.periodStart, target.periodEnd, parsed.courses);
                ui.post(() -> {
                    if (!live()) return;
                    screen.close(); screen.finishSelection.run();
                    XitImportPreview.open(screen.activity, frozen, parsed, draft);
                });
            } catch (Exception e) { ui.post(() -> { if (live()) fail(e.getMessage()); }); }
        }, "course-xit-preview").start();
    }
    private void fail(String reason) {
        if (closed) return;
        screen.message(reason == null || reason.isEmpty() ? "拾光解析失败" : reason);
        close();
    }
    void close() {
        if (closed) return;
        closed = true; ui.removeCallbacks(timeout);
        if (prompt != null) { prompt.dismiss(); prompt = null; }
        if (port != null) { try { port.close(); } catch (RuntimeException ignored) {} port = null; }
    }
}
