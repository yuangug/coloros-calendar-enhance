package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Native create-sheet results delivered to the enhanced main Activity. */
final class HostCourseImport {
    private static final int OFFSET = 0x5000;
    private static final String PENDING = "lspx_course_image_request";

    static int routeRequest(Activity activity, int request) {
        if (!TableEntryRoute.MAIN.equals(activity.getClass().getName())
                || (request != 1000 && request != 1100 && request != 1200)) return request;
        // The same Activity hosts other import/scan features. Only launches originating
        // in the native course helper own these results, including permission callbacks.
        boolean courseLaunch = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
            if (frame.getClassName().startsWith(HostCompat.CREATE_HELPER)) { courseLaunch = true; break; }
        if (!courseLaunch) return request;
        activity.getIntent().putExtra(PENDING, request);
        MainTabHook.keepTableMode();
        Logx.i(CourseAlarmEntry.TAG, "course image launch request=" + request);
        return OFFSET + request;
    }

    static void save(Activity activity, Bundle state) {
        if (state != null) state.putInt(PENDING, activity.getIntent().getIntExtra(PENDING, 0));
    }

    static void restore(Activity activity, Bundle state) {
        if (state != null && state.getInt(PENDING, 0) != 0)
            activity.getIntent().putExtra(PENDING, state.getInt(PENDING));
    }

    static boolean result(Activity activity, int request, int result, Intent data) {
        int nativeRequest = request - OFFSET;
        if (nativeRequest != 1000 && nativeRequest != 1100 && nativeRequest != 1200) return false;
        if (activity.getIntent().getIntExtra(PENDING, 0) != nativeRequest) {
            Logx.w(CourseAlarmEntry.TAG, "忽略不属于当前选图流程的结果: " + request);
            return true;
        }
        activity.getIntent().removeExtra(PENDING);
        MainTabHook.keepTableMode();
        boolean cleanup = nativeRequest == 1200;
        try {
            if (result == Activity.RESULT_CANCELED) return true;
            Uri image = null;
            int ocrType;
            if (nativeRequest == 1000) {
                // The native document camera returns its own result code and a URI list.
                if (result == Activity.RESULT_OK) return true;
                ArrayList<Uri> images = data == null ? null : data.getParcelableArrayListExtra("excelImageUris");
                if (images != null && !images.isEmpty()) image = images.get(0);
                ocrType = 1;
            } else {
                if (result != Activity.RESULT_OK) throw new IllegalStateException("相册未返回可用图片");
                image = data == null ? null : data.getData();
                if (image == null && data != null) image = data.getParcelableExtra("output");
                if (image == null) image = HostCompat.lastCropImage(activity.getClassLoader());
                cleanup = true;
                ocrType = 2; // Same path as native SchoolTimeTableActivity.handleEditSuccess.
            }
            if (image == null) throw new IllegalStateException("未收到图片，请重新选择");
            Intent scan = new Intent().setClassName(activity.getPackageName(), "com.android.calendar.ui.activity.ScanActivity")
                    .putExtra("pic_uri", image).putExtra("ocr_type", ocrType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            scan.setClipData(ClipData.newRawUri("course image", image));
            activity.startActivity(scan);
            Logx.i(CourseAlarmEntry.TAG, "course image -> native ScanActivity ocr_type=" + ocrType);
        } catch (Exception error) {
            Logx.e(CourseAlarmEntry.TAG, "原生课表图片导入失败", error);
            CurriculumIo.toast(activity, "无法导入图片：" + error.getMessage());
        } finally {
            if (cleanup) cleanup(activity);
        }
        return true;
    }

    private static void cleanup(Activity activity) {
        try {
            Method sheet = HostCompat.createSheet(activity.getClassLoader());
            Method cleanup = sheet.getDeclaringClass().getDeclaredMethod(sheet.getName().equals("G") ? "B" : "I", Context.class);
            cleanup.setAccessible(true);
            cleanup.invoke(null, activity);
        } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "清理原生选图临时授权", error); }
    }
}
