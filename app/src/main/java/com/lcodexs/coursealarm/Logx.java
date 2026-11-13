package com.lcodexs.coursealarm;

import android.util.Log;

/** 统一日志出口：tag 固定，方便 `adb logcat -s COURSEALARM` 一把抓。 */
final class Logx {
    private Logx() {
    }

    static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
