package com.lcodexs.coursealarm;

import android.app.Activity;

import java.lang.reflect.Method;

/**
 * 「创建课表」—— **调用宿主原生的那个底部面板**，不自己画。
 *
 * 宿主 SchoolTimeTableActivity 里点「创建课表」走的是
 *   ImportCourseTableHelper.G/N(FragmentActivity, boolean)
 *   → COUIBottomSheetDialog + R.layout.dialog_create_course
 *   → 三个入口：拍照导入（coloros.intent.action.EXCEL_SCANNER）/ 相册导入 / 手动创建
 *
 * 模块以前直接 startActivity(CreateCourseTableActivity) —— 那只是「手动创建」那一支，
 * 拍照 / 相册两条入口就这样被吃掉了。这里改回复用宿主方法，样式与原生逐像素一致
 * （同一个 dialog 类、同一个 style、同一份 layout 和图标资源）。
 */
final class HostCreateSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private HostCreateSheet() {
    }

    /**
     * @return true = 已弹出宿主面板；false = 宿主这一版没有该方法，调用方自行兜底
     */
    static boolean show(Activity a) {
        if (a == null) return false;
        ClassLoader host = a.getClassLoader();
        try {
            Method target = HostCompat.createSheet(host);
            if (!target.getParameterTypes()[0].isInstance(a)) return false;
            target.invoke(null, a, Boolean.FALSE);
            Logx.i(T, "创建课表：已弹宿主原生面板 method=" + target.getName()
                    + "（拍照导入 / 相册导入 / 手动创建）");
            return true;
        } catch (Throwable t) {
            Logx.e(T, "创建课表：弹宿主面板失败", t);
            return false;
        }
    }
}
