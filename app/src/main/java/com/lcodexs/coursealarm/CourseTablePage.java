package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 主页里的「课程表」视图 —— 和「日历 / 待办」同一个页面，只换内容区。
 *
 * 宿主自己的课表是独立 Activity（带自己的 toolbar），这里不用它：直接读课表数据自绘，
 * 塞进主界面的 fragment_container，底栏（sliding_date_picker）本来就在它外面，所以切换后底栏仍在。
 *
 * 数据全部来自 {@link TableCtx}（与导入/导出/闹钟同一份来源），主题背景走 {@link ThemeBg}。
 */
final class CourseTablePage {
    private static final String T = CourseAlarmEntry.TAG;
    static final String TAG_VIEW = "lspx_course_page";
    private static final String TAG_BG = "lspx_course_page_bg";

    private CourseTablePage() {
    }

    static boolean isShowing(Activity a) {
        if (a == null) return false;
        ViewGroup host = container(a);
        return host != null && host.findViewWithTag(TAG_VIEW) != null;
    }

    static void show(Activity a) {
        if (a == null) return;
        ViewGroup host = container(a);
        if (host == null) {
            Logx.w(T, "coursepage: fragment_container not found");
            return;
        }
        if (host.findViewWithTag(TAG_VIEW) != null) {
            Logx.i(T, "coursepage: already showing");
            return;
        }
        CoursePageView page = new CoursePageView(a, host);
        page.setTag(TAG_VIEW);
        host.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            page.bringToFront();
            page.setElevation(4f);
        } catch (Throwable ignored) {
        }
        // 底栏悬浮在上面，给网格留出高度，别压住最后一节
        int reserve = Math.round((44 + 12) * a.getResources().getDisplayMetrics().density) + navBar(a);
        page.setPadding(0, 0, 0, reserve);
        retitle(a, true);
        Logx.i(T, "coursepage: shown in " + host.getClass().getSimpleName()
                + " children=" + host.getChildCount());
        page.reload();
    }

    static void hide(Activity a) {
        if (a == null) return;
        ViewGroup host = container(a);
        if (host == null) return;
        View v = host.findViewWithTag(TAG_VIEW);
        if (v != null) {
            host.removeView(v);
            retitle(a, false);
            Logx.i(T, "coursepage: hidden");
        }
    }

    /** 主题变了以后重绘 */
    static void refresh(Activity a) {
        if (a == null) return;
        ViewGroup host = container(a);
        if (host == null) return;
        View v = host.findViewWithTag(TAG_VIEW);
        if (v instanceof CoursePageView) ((CoursePageView) v).applyTheme();
    }

    /** 顶栏标题：进课表页显示「课程表」，回来时还原 */
    private static void retitle(Activity a, boolean course) {
        int titleId = a.getResources().getIdentifier("tv_title", "id", a.getPackageName());
        int weekId = a.getResources().getIdentifier("tv_week", "id", a.getPackageName());
        TextView title = (TextView) a.findViewById(titleId);
        View week = a.findViewById(weekId);
        if (title == null) return;
        if (course) {
            if (savedTitle == null) savedTitle = String.valueOf(title.getText());
            title.setText("课程表");
            if (week != null) week.setVisibility(View.GONE);
        } else {
            if (savedTitle != null) title.setText(savedTitle);
            if (week != null) week.setVisibility(View.VISIBLE);
        }
    }

    private static String savedTitle = null;

    private static int navBar(Activity a) {
        try {
            int id = a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return a.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return Math.round(16 * a.getResources().getDisplayMetrics().density);
    }
    private static ViewGroup container(Activity a) {
        int id = a.getResources().getIdentifier("fragment_container", "id", a.getPackageName());
        View v = a.findViewById(id);
        return v instanceof ViewGroup ? (ViewGroup) v : null;
    }

    // ── 页面：顶栏（周次切换）+ 自绘网格 ──────────────────────────────────

    static final class CoursePageView extends FrameLayout {
        private final Activity act;
        private final TextView weekTitle;
        private final GridView grid;
        private final LinearLayout topBar;
        private TableCtx table;
        private int week = 1;

        CoursePageView(Activity a, ViewGroup parent) {
            super(a);
            this.act = a;
            setClipChildren(false);

            topBar = new LinearLayout(a);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            tlp.gravity = Gravity.TOP;
            addView(topBar, tlp);

            TextView prev = arrow(a, "‹");
            topBar.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));
            prev.setOnClickListener(v -> shiftWeek(-1));

            weekTitle = new TextView(a);
            weekTitle.setGravity(Gravity.CENTER);
            weekTitle.setTextSize(15);
            weekTitle.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            topBar.addView(weekTitle, wlp);

            TextView next = arrow(a, "›");
            topBar.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
            next.setOnClickListener(v -> shiftWeek(1));

            grid = new GridView(a, this);
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            glp.topMargin = dp(48);
            addView(grid, glp);

            applyTheme();
        }

        /** 主题：底色/渐变/图片(含 gif) 铺在本页最底层，网格线与卡片画在上面 */
        void applyTheme() {
            boolean night = (act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            int fg = night ? 0xFFF0F1F3 : 0xFF1A1A1A;
            weekTitle.setTextColor(fg);
            grid.setColors(night);

            View old = findViewWithTag(TAG_BG);
            if (old != null) removeView(old);
            // 先铺不透明底：宿主日历视图就在同层附近，透明会把它透出来
            setBackgroundColor(night ? 0xFF101216 : 0xFFFFFFFF);

            int type = ThemeStore.type(act);
            if (type == ThemeStore.TYPE_SOLID) {
                int idx = Math.max(0, Math.min(ThemeStore.solidIndex(act), ThemeStore.SOLIDS.length - 1));
                setBackgroundColor(ThemeStore.SOLIDS[idx]);
            } else if (type == ThemeStore.TYPE_GRADIENT) {
                int idx = Math.max(0, Math.min(ThemeStore.gradientIndex(act), ThemeStore.GRADIENTS.length - 1));
                setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        ThemeStore.GRADIENTS[idx]));
            } else if (type == ThemeStore.TYPE_IMAGE) {
                java.io.File f = ThemeStore.imageFile(act);
                if (f != null) {
                    View bg = null;
                    if (f.getName().toLowerCase().endsWith(".gif")) {
                        ThemeBg.GifView g = new ThemeBg.GifView(act);
                        g.setSource(f);
                        bg = g;
                    } else {
                        android.widget.ImageView iv = new android.widget.ImageView(act);
                        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                        android.graphics.Bitmap bmp =
                                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                        if (bmp != null) {
                            iv.setImageBitmap(bmp);
                            bg = iv;
                        }
                    }
                    if (bg != null) {
                        bg.setTag(TAG_BG);
                        bg.setAlpha(Math.max(0, Math.min(255,
                                (int) (255 * ThemeStore.imageAlpha(act) / 100f))));
                        addView(bg, 0, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    }
                }
            }
            grid.invalidate();
        }

        /** 后台读一次课表数据，再刷网格 */
        void reload() {
            new Thread(() -> {
                TableCtx t = null;
                try {
                    t = TableCtx.load(act);
                } catch (Throwable e) {
                    Logx.e(T, "coursepage: load failed", e);
                }
                final TableCtx loaded = t;
                act.runOnUiThread(() -> {
                    table = loaded;
                    if (loaded != null) {
                        week = clampWeek(currentWeek(loaded), loaded.weekCount);
                    }
                    updateTitle();
                    grid.invalidate();
                    Logx.i(T, "coursepage: loaded courses=" + (loaded == null ? -1 : loaded.courses.size())
                            + " week=" + week);
                });
            }, "lspx-coursepage").start();
        }

        private void shiftWeek(int d) {
            if (table == null) return;
            int w = clampWeek(week + d, table.weekCount);
            if (w == week) return;
            week = w;
            updateTitle();
            grid.invalidate();
        }

        private void updateTitle() {
            if (table == null) {
                weekTitle.setText("第 -- 周");
                return;
            }
            weekTitle.setText("第 " + week + " 周");
        }

        private static int currentWeek(TableCtx t) {
            if (t.termBeginMs <= 0) return 1;
            long d = System.currentTimeMillis() - t.termBeginMs;
            return (int) (d / (7L * 86400000L)) + 1;
        }

        private static int clampWeek(int w, int max) {
            return Math.max(1, Math.min(w, Math.max(max, 1)));
        }

        private static TextView arrow(Activity a, String s) {
            TextView tv = new TextView(a);
            tv.setText(s);
            tv.setTextSize(22);
            tv.setGravity(Gravity.CENTER);
            tv.setClickable(true);
            tv.setFocusable(true);
            return tv;
        }

        int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        TableCtx table() {
            return table;
        }

        int week() {
            return week;
        }
    }

    // ── 网格：自绘（周次头 + 节次列 + 课程卡片）─────────────────────────────

    static final class GridView extends View {
        private static final int[] PALETTE = {
                0xFFE8F0FF, 0xFFE6F7EF, 0xFFFFF3E0, 0xFFFFE9F0, 0xFFF0E9FF,
                0xFFE8F7F9, 0xFFFFF7D6, 0xFFEDEFF3, 0xFFE9F3E2, 0xFFFDEBE4,
                0xFFE3F2FD, 0xFFF3E5F5, 0xFFE0F2F1, 0xFFFFF8E1, 0xFFF1F8E9
        };
        private static final int[] INK = {
                0xFF2E5BFF, 0xFF1B8A5A, 0xFFB26A00, 0xFFB0306A, 0xFF5E35B1,
                0xFF00796B, 0xFF827717, 0xFF455A64, 0xFF33691E, 0xFFBF360C,
                0xFF1565C0, 0xFF6A1B9A, 0xFF00695C, 0xFF8D6E63, 0xFF558B2F
        };

        private final CoursePageView page;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int nightFg = 0xFF1A1A1A;
        private int nightSub = 0xFF7A808C;
        private int gridLine = 0x14000000;

        GridView(Context c, CoursePageView page) {
            super(c);
            this.page = page;
            tp.setTypeface(Typeface.DEFAULT);
        }

        void setColors(boolean night) {
            nightFg = night ? 0xFFF0F1F3 : 0xFF1A1A1A;
            nightSub = night ? 0xFF9AA0AB : 0xFF7A808C;
            gridLine = night ? 0x1FFFFFFF : 0x10000000;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TableCtx t = page.table();
            int w = getWidth(), h = getHeight();
            float dens = getResources().getDisplayMetrics().density;

            float padL = 6 * dens, padT = 4 * dens;
            float nodeW = 46 * dens;         // 左侧节次列
            float headH = 46 * dens;         // 星期行
            int nodes = t == null ? 10 : Math.max(1, t.nodeCount());
            float dayW = (w - padL * 2 - nodeW) / 7f;
            float rowH = Math.max(48 * dens, (h - padT * 2 - headH) / nodes);

            // 表头：周一…周日 + 日期
            tp.setTextSize(11 * dens);
            tp.setColor(nightSub);
            tp.setTextAlign(Paint.Align.CENTER);
            String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            long[] dates = weekDates(t, page.week());
            for (int d = 0; d < 7; d++) {
                float cx = padL + nodeW + dayW * d + dayW / 2f;
                tp.setFakeBoldText(true);
                tp.setColor(nightFg);
                canvas.drawText(names[d], cx, padT + 14 * dens, tp);
                tp.setFakeBoldText(false);
                tp.setColor(nightSub);
                if (dates[d] > 0) {
                    canvas.drawText(fmtDate(dates[d]), cx, padT + 30 * dens, tp);
                }
            }

            // 节次列
            for (int n = 0; n < nodes; n++) {
                float y = padT + headH + rowH * n;
                tp.setColor(nightSub);
                tp.setTextSize(11 * dens);
                canvas.drawText(String.valueOf(n + 1), padL + nodeW / 2f, y + rowH / 2f - 2 * dens, tp);
                if (t != null) {
                    tp.setTextSize(8.5f * dens);
                    String a = fmtMinute(t.periodStart[n]), b = fmtMinute(t.periodEnd[n]);
                    canvas.drawText(a, padL + nodeW / 2f, y + rowH / 2f + 10 * dens, tp);
                    canvas.drawText(b, padL + nodeW / 2f, y + rowH / 2f + 20 * dens, tp);
                }
            }

            // 网格线
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(gridLine);
            for (int d = 0; d <= 7; d++) {
                float x = padL + nodeW + dayW * d;
                canvas.drawLine(x, padT + headH - 6 * dens, x, padT + headH + rowH * nodes, p);
            }
            for (int n = 0; n <= nodes; n++) {
                float y = padT + headH + rowH * n;
                canvas.drawLine(padL + nodeW, y, w - padL, y, p);
            }

            if (t == null) {
                tp.setColor(nightSub);
                tp.setTextSize(13 * dens);
                canvas.drawText("正在读取课表…", w / 2f, h / 2f, tp);
                return;
            }

            // 课程卡片
            for (Course c : t.courses) {
                if (c.day < 0 || c.day > 6) continue;
                boolean thisWeek = c.weeks.get(page.week());
                float x0 = padL + nodeW + dayW * c.day + 2 * dens;
                float x1 = x0 + dayW - 4 * dens;
                float y0 = padT + headH + rowH * t.safeNode(c.startNode) + 2 * dens;
                float y1 = y0 + rowH * (t.safeNode(c.endNode) - t.safeNode(c.startNode) + 1) - 4 * dens;
                if (y1 > padT + headH + rowH * nodes) y1 = padT + headH + rowH * nodes - 2 * dens;
                if (y1 - y0 < 12 * dens) continue;

                int idx = Math.abs(c.color) % PALETTE.length;
                p.setStyle(Paint.Style.FILL);
                p.setColor(thisWeek ? PALETTE[idx] : 0x33808080);
                r.set(x0, y0, x1, y1);
                canvas.drawRoundRect(r, 7 * dens, 7 * dens, p);

                // 文字（块太矮就只画一行）
                float textX = x0 + 4 * dens;
                float maxW = x1 - textX - 3 * dens;
                int base = thisWeek ? INK[idx] : 0xFF9AA0AB;
                tp.setColor(base);
                tp.setTextAlign(Paint.Align.LEFT);
                tp.setTypeface(Typeface.DEFAULT_BOLD);
                tp.setTextSize(10.5f * dens);
                float ty = y0 + 13 * dens;
                for (String line : wrap(c.name, maxW, 2)) {
                    if (ty > y1 - 3 * dens) break;
                    canvas.drawText(line, textX, ty, tp);
                    ty += 12 * dens;
                }
                tp.setTypeface(Typeface.DEFAULT);
                tp.setTextSize(9 * dens);
                tp.setColor(thisWeek ? 0xCC000000 : 0xFFAAAAAA);
                if (c.room != null && !c.room.isEmpty() && ty < y1 - 2 * dens) {
                    for (String line : wrap(c.room, maxW, 1)) {
                        if (ty > y1 - 2 * dens) break;
                        canvas.drawText(line, textX, ty + 2 * dens, tp);
                        ty += 10 * dens;
                    }
                }
                if (!thisWeek && (y1 - y0) > 40 * dens) {
                    tp.setColor(0xFF9AA0AB);
                    tp.setTextSize(8 * dens);
                    tp.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText("非本周", x1 - 4 * dens, y1 - 5 * dens, tp);
                    tp.setTextAlign(Paint.Align.LEFT);
                }
                tp.setTypeface(Typeface.DEFAULT);
            }
        }

        /** 简单按宽度断行（中文按字符切） */
        private List<String> wrap(String s, float maxW, int maxLines) {
            List<String> out = new ArrayList<>();
            if (s == null || s.isEmpty()) return out;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                line.append(ch);
                if (tp.measureText(line.toString()) > maxW) {
                    line.deleteCharAt(line.length() - 1);
                    if (line.length() > 0) out.add(line.toString());
                    line.setLength(0);
                    line.append(ch);
                    if (out.size() >= maxLines) return out;
                }
            }
            if (line.length() > 0 && out.size() < maxLines) out.add(line.toString());
            return out;
        }

        private static String fmtMinute(int m) {
            return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
        }

        private static String fmtDate(long ms) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ms);
            return String.format(java.util.Locale.US, "%d/%d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        }

        /** 该周 7 天的日期（termBegin = 第 1 周周一 0 点） */
        private static long[] weekDates(TableCtx t, int week) {
            long[] out = new long[7];
            if (t == null || t.termBeginMs <= 0) return out;
            for (int d = 0; d < 7; d++) {
                out[d] = t.termBeginMs + (long) (week - 1) * 7L * 86400000L + (long) d * 86400000L;
            }
            return out;
        }
    }
}
