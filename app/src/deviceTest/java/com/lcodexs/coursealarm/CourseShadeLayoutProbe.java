package com.lcodexs.coursealarm;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Runs off-screen on the verification application's initialized main thread, including Android 17. */
public final class CourseShadeLayoutProbe {
    static void run(Context base) {
        Context ctx = new ContextThemeWrapper(base, android.R.style.Theme_Material_Light);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            for (boolean active : new boolean[]{false, true}) {
                LinearLayout section = new LinearLayout(ctx);
                section.setOrientation(LinearLayout.VERTICAL);
                section.setLayoutDirection(direction);
                TextView status = new TextView(ctx);
                status.setText("距上课 20 分钟");
                section.addView(new View(ctx), 1, 3);
                LinearLayout.LayoutParams original = new LinearLayout.LayoutParams(-1, -2);
                section.addView(status, original);
                // The E16 widget requires the live Seedling engine; this probe covers its row only.
                TextView action = active ? new TextView(ctx) : new android.widget.Button(ctx);
                action.setSingleLine(true);
                action.setText(CourseDndClient.label(active, false, true));
                if (!active) action.setOnClickListener(v -> {});
                CourseCardActionRow row = new CourseCardActionRow(section, status, action);
                row.mount();
                int width = Math.round(320 * ctx.getResources().getDisplayMetrics().density);
                section.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                section.layout(0, 0, width, section.getMeasuredHeight());
                if (!row.containsStatus(status) || section.getChildCount() != 2 || row.getChildCount() != 2
                        || Math.abs(status.getTop() + status.getHeight()/2 - action.getTop() - action.getHeight()/2) > 1
                        || action.getWidth() == 0 || action.isClickable() == active)
                    throw new AssertionError("status and action must share a centered row");
                if (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? action.getRight() > status.getLeft() : status.getRight() > action.getLeft())
                    throw new AssertionError("text overlaps action");
                row.release();
                if (section.getChildAt(1) != status || status.getLayoutParams() != original || action.getParent() != null)
                    throw new AssertionError("original layout not restored");
            }
        }
    }
}
