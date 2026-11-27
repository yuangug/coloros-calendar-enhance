package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Report actual publisher/template capabilities, never infer rendering from extra JSON fields. */
final class CourseLiveCapabilities {
    static volatile boolean notificationHook, fluidHook;

    static void reportNative(Context context, CourseOccurrence item, boolean rendered, String reason) {
        SharedPreferences reports = reports(context);
        String prefix = "native_" + item.tableIdentity + "_";
        CourseLiveSettings.State state = CourseLiveCoordinator.settings(item);
        reports.edit().putBoolean("nativeRendered", rendered).putString("nativeReason", reason)
                .putLong("reported", System.currentTimeMillis())
                .putBoolean(prefix + "rendered", rendered).putString(prefix + "reason", reason)
                .putLong(prefix + "reported", System.currentTimeMillis())
                .putLong(prefix + "expires", state == null ? item.end : item.expiresAt(state.retainMinutes))
                .apply();
    }

    static void reportAstra(Context context, CourseOccurrence item, String status) {
        SharedPreferences reports = reports(context);
        String prefix = "astra_" + item.tableIdentity + "_";
        long now = System.currentTimeMillis();
        CourseLiveSettings.State settings = CourseLiveCoordinator.settings(item);
        reports.edit().putString(prefix + "status", status).putString(prefix + "key", item.key)
                .putLong(prefix + "reported", now)
                .putLong(prefix + "expires", "active".equals(status) && settings != null
                        ? item.expiresAt(settings.retainMinutes) : now).apply();
    }

    static String status(Context context, CourseLiveSettings.State settings, android.os.Bundle scope) {
        if (settings == null) return "课表状态不可用";
        SharedPreferences reports = reports(context);
        long now = System.currentTimeMillis();
        StringBuilder value = new StringBuilder();
        value.append("通知栏：");
        if (!settings.enabled) value.append("已关闭");
        else if (scope == null || !scope.getBoolean("success")) value.append(scope != null && scope.getBoolean("known")
                ? "已开启，等待系统界面作用域授权" : "已开启，暂未确认作用域状态");
        else {
            String prefix = "native_" + settings.identity + "_";
            long expiry = reports.getLong(prefix + "expires", 0);
            if (expiry <= now) value.append("已开启，当前没有活动课程卡片");
            else if (!reports.getBoolean(prefix + "rendered", false))
                value.append("卡片已出现，原生模板尚未显示增强控件");
            else value.append(switch (reports.getString(prefix + "reason", "")) {
                case "native_progress_and_action" -> "已显示进度条和免打扰状态或操作";
                case "native_progress_only" -> "已显示进度条，免打扰按钮不可用";
                case "native_action_only" -> "已显示免打扰状态或操作";
                default -> "已显示增强卡片";
            });
        }
        value.append("\n星河岛：");
        if (!settings.astraEnabled) value.append("已关闭");
        else if (!CourseSeedlingRouting.available) value.append("课程卡片接管入口未就绪");
        else {
            String prefix = "astra_" + settings.identity + "_";
            String status = reports.getString(prefix + "status", "");
            long reported = reports.getLong(prefix + "reported", 0);
            long expiry = reports.getLong(prefix + "expires", 0);
            if ("active".equals(status) && expiry > now) value.append("已接管课程胶囊");
            else if ("connecting".equals(status) && now - reported < 120000L) value.append("正在连接星河岛");
            else if ("missing".equals(status) && now - reported < 600000L)
                value.append("未检测到星河岛，保留日历原生胶囊");
            else if ("unavailable".equals(status) && now - reported < 600000L)
                value.append("星河岛未接收课程卡片，将自动重试");
            else if ("ended".equals(status)) value.append("当前没有活动课程卡片");
            else value.append("已开启，等待课程提醒卡片");
        }
        value.append("\n息屏：");
        if (CourseLiveSeedlingState.aodSubscribed(settings.identity)) value.append("系统息屏已订阅课程卡片");
        else if (settings.astraEnabled) value.append("星河岛可显示课程名，需在星流「显示规则」中开启锁屏显示和息屏显示");
        else value.append("当前未收到系统息屏订阅；已检查的三版日历模板未声明息屏入口");
        if (settings.keepDuringCourse) value.append("\n持续显示：已开启，按本课表的课前提醒时间至下课");
        return value.toString();
    }

    static String summary(Context context) {
        SharedPreferences reports = reports(context);
        String nativeStatus = switch (reports.getString("nativeReason", "")) {
            case "native_progress_and_action" -> "原生卡片已显示进度条及免打扰状态或操作";
            case "native_progress_only" -> "原生卡片已显示进度条，本次未显示免打扰按钮";
            case "native_action_only" -> "原生卡片已显示免打扰状态或操作，本次未显示进度条";
            case "native_controls_disabled" -> "本次卡片未启用增强控件";
            default -> reports.contains("nativeRendered") ? "尚未确认当前原生模板支持增强控件"
                    : "尚未收到系统界面的实际渲染确认";
        };
        return "日历版本：" + version(context) + "\n"
                + "原生实时通知：" + nativeStatus + "；订阅成功后只保留这张卡片\n"
                + "普通课程通知：原生实时活动不可用时，保留宿主课程提醒\n"
                + "流体云：" + (CourseSeedlingRouting.available ? "星河岛接管成功后，仅替换课程胶囊和展开卡片"
                        : "当前宿主不支持分开路由，保留原生流体云") + "\n"
                + "负一屏／小布建议：保留原生课程卡片；当前模板没有可用进度条接口，尚未完成图形进度适配\n"
                + "系统界面仅处理日历课程实时卡片，不修改系统安装包或签名模板。";
    }

    private static SharedPreferences reports(Context context) {
        return context.getSharedPreferences("lspx_course_live_capabilities", Context.MODE_PRIVATE);
    }

    private static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo("com.coloros.calendar", 0).versionName
                + " / Android " + Build.VERSION.RELEASE; }
        catch (Exception ignored) { return "未知"; }
    }
}
