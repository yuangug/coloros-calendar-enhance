package com.lcodexs.coursealarm;

import android.content.Context;
import android.os.Build;

/** Report actual publisher/template capabilities, never infer rendering from extra JSON fields. */
final class CourseLiveCapabilities {
    static volatile boolean notificationHook, fluidHook;

    static String summary(Context context) {
        android.content.SharedPreferences reports = context.getSharedPreferences("lspx_course_live_capabilities", Context.MODE_PRIVATE);
        String nativeStatus = switch (reports.getString("nativeReason", "")) {
            case "native_progress_and_action" -> "原生卡片已显示进度条及免打扰按钮";
            case "native_progress_only" -> "原生卡片已显示进度条，本次未显示免打扰按钮";
            case "native_action_only" -> "原生卡片已显示免打扰按钮，本次未显示进度条";
            case "native_controls_disabled" -> "本次卡片未启用增强控件";
            default -> reports.contains("nativeRendered") ? "尚未确认当前原生模板支持增强控件"
                    : "尚未收到系统界面的实际渲染确认";
        };
        String fluidStatus = switch (reports.getString("fluidReason", "")) {
            case "native_progress_only" -> "展开卡片已显示课程进度条";
            case "native_controls_disabled" -> "本次卡片未显示进度条";
            case "native_divider_missing", "layout_incompatible" -> "当前模板不兼容，保留原生分隔线";
            default -> "尚未确认展开卡片的进度条";
        };
        return "日历版本：" + version(context) + "\n"
                + "原生实时通知：" + nativeStatus + "；订阅成功后只保留这张卡片\n"
                + "普通课程通知：" + (notificationHook ? "原生实时通知不可用时保留；支持课程进度与免打扰" : "未接通，保留原生通知") + "\n"
                + "流体云：" + (fluidHook ? "支持点击保留及课程状态；" + fluidStatus : "未接通，保留原生卡片") + "\n"
                + "负一屏／小布建议：保留原生课程卡片；当前模板没有可用进度条接口，尚未完成图形进度适配\n"
                + "系统界面仅处理日历课程实时卡片，不修改系统安装包或签名模板。";
    }

    private static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo("com.coloros.calendar", 0).versionName
                + " / Android " + Build.VERSION.RELEASE; }
        catch (Exception ignored) { return "未知"; }
    }
}
