package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import java.util.List;

/** Module-side LSPosed service. The calendar can request exactly one package. */
final class CourseSystemUiScope {
    static final String REQUEST = "requestSystemUiScope";
    static final String STATUS = "systemUiScopeStatus";
    static final String PACKAGE = "com.android.systemui";
    static final String REPLY = "reply";
    private static final Uri PROVIDER = Uri.parse("content://" + CourseDndClient.AUTHORITY);

    static Bundle request(Context context, ResultReceiver reply) {
        Bundle args = new Bundle();
        args.putParcelable(REPLY, reply);
        return call(context, REQUEST, args);
    }

    static Intent bootstrap(Context context, ResultReceiver reply) {
        return new Intent().setClassName(CourseDndClient.PACKAGE, CourseSystemUiScopeActivity.class.getName())
                .putExtra(REPLY, reply);
    }

    static Bundle status(Context context) { return call(context, STATUS, null); }

    private static Bundle call(Context context, String method, Bundle args) {
        try {
            Bundle result = context.getContentResolver().call(PROVIDER, method, null, args);
            if (result != null) return result;
        } catch (RuntimeException ignored) { }
        return outcome(false, "无法连接模块服务，请检查模块是否已安装并启用");
    }

    private static Bundle outcome(boolean success, String message) {
        Bundle result = new Bundle();
        result.putBoolean("success", success);
        result.putString("message", message);
        return result;
    }

    // Only loaded by the module application. No framework binder is exposed to the host.
    static final class Broker implements io.github.libxposed.service.XposedServiceHelper.OnServiceListener {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final Broker INSTANCE = new Broker();
        private volatile io.github.libxposed.service.XposedService service;
        private Request pending;

        static void init() {
            io.github.libxposed.service.XposedServiceHelper.registerListener(INSTANCE);
        }

        static Bundle handle(String method, Bundle args) {
            if (STATUS.equals(method)) {
                var service = INSTANCE.service;
                try {
                    if (service != null && service.getScope().contains(PACKAGE))
                        return outcome(true, "系统界面作用域已授权");
                } catch (RuntimeException ignored) { }
                return outcome(false, service == null ? "尚未连接 LSPosed" : "系统界面作用域未授权");
            }
            ResultReceiver reply = args == null ? null : args.getParcelable(REPLY);
            if (reply == null) return outcome(false, "无效的作用域请求");
            MAIN.post(() -> INSTANCE.start(reply));
            return outcome(true, null);
        }

        private void start(ResultReceiver reply) {
            if (pending != null) {
                reply.send(0, outcome(false, "已有作用域申请，请先处理 LSPosed 提示"));
                return;
            }
            Request request = new Request(reply);
            pending = request;
            MAIN.postDelayed(request.timeout, 8000);
            submit();
        }

        private void submit() {
            Request request = pending;
            var connected = service;
            if (request == null || request.submitted || connected == null) return;
            request.submitted = true;
            MAIN.removeCallbacks(request.timeout);
            MAIN.postDelayed(request.timeout, 90000);
            try {
                if (connected.getScope().contains(PACKAGE)) {
                    request.finish(true, null);
                    return;
                }
                connected.requestScope(List.of(PACKAGE),
                        new io.github.libxposed.service.XposedService.OnScopeEventListener() {
                    @Override public void onScopeRequestApproved(List<String> approved) {
                        MAIN.post(() -> {
                            try {
                                boolean granted = approved.contains(PACKAGE) && connected.getScope().contains(PACKAGE);
                                request.finish(granted, granted ? null : "未获得系统界面作用域");
                            } catch (RuntimeException error) {
                                request.finish(false, "无法确认系统界面作用域");
                            }
                        });
                    }

                    @Override public void onScopeRequestFailed(String message) {
                        MAIN.post(() -> request.finish(false, "LSPosed 未批准作用域申请，请在管理器中检查"));
                    }
                });
            } catch (RuntimeException error) {
                request.finish(false, "无法向 LSPosed 申请作用域");
            }
        }

        @Override public void onServiceBind(io.github.libxposed.service.XposedService service) {
            this.service = service;
            MAIN.post(this::submit);
        }

        @Override public void onServiceDied(io.github.libxposed.service.XposedService service) {
            if (this.service == service) this.service = null;
        }

        private final class Request {
            final ResultReceiver reply;
            boolean submitted;
            final Runnable timeout = () -> finish(false, submitted ? "等待 LSPosed 确认超时" : "未连接 LSPosed，请检查模块是否已启用");

            Request(ResultReceiver reply) { this.reply = reply; }

            void finish(boolean granted, String message) {
                if (pending != this) return;
                MAIN.removeCallbacks(timeout);
                pending = null;
                reply.send(granted ? 1 : 0, outcome(granted, message));
            }
        }
    }
}
