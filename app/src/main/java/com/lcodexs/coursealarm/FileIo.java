package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 文件落盘/取读：优先系统「下载」目录（MediaStore，无需权限），失败退回应用外部目录。 */
final class FileIo {
    private static final String T = CourseAlarmEntry.TAG;

    private FileIo() {
    }

    static Uri saveToDownloads(Context ctx, String fileName, String mime, byte[] data) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                // 同名先删：否则 MediaStore 会自动改名成「课程表_xxx (1).xlsx」，
                // 用户点开旧文件看到的还是上一次的内容（实测踩到，表现为「导出是空白」）
                try {
                    int removed = ctx.getContentResolver().delete(collection,
                            MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{fileName});
                    Logx.i(T, "overwrite: removed " + removed + " old file(s) named " + fileName);
                } catch (Throwable t) {
                    Logx.w(T, "overwrite cleanup failed: " + t);
                }
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri item = ctx.getContentResolver().insert(collection, cv);
                if (item != null) {
                    try (OutputStream os = ctx.getContentResolver().openOutputStream(item)) {
                        if (os != null) os.write(data);
                    }
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    ctx.getContentResolver().update(item, cv, null, null);
                    Logx.i(T, "saved to Downloads: " + item + " (" + data.length + " bytes)");
                    return item;
                }
            } catch (Throwable t) {
                Logx.w(T, "MediaStore save failed: " + t);
            }
        }
        try {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = ctx.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            Logx.i(T, "saved to " + f);
            return Uri.fromFile(f);
        } catch (Throwable t) {
            Logx.e(T, "file save failed", t);
            return null;
        }
    }

    static byte[] readAll(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            Logx.e(T, "readAll failed: " + uri, t);
            return null;
        }
    }

    static String displayName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
    }

    static void share(Activity a, Uri uri, String mime, String title) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, title));
        } catch (Throwable t) {
            Logx.w(T, "share failed: " + t);
        }
    }
}
