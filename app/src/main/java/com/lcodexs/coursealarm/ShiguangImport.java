package com.lcodexs.coursealarm;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ShiguangImport {
    static final class Source {
        final ShiguangCatalog.Adapter adapter;
        final String script, bridge;
        Source(ShiguangCatalog.Adapter a) throws Exception {
            adapter = a;
            script = a.localXit ? "" : ShiguangCatalog.script(a);
            bridge = a.localXit ? ShiguangCatalog.bundledScript("xit.js") : ShiguangCatalog.bridge();
        }
    }

    static void open(Activity activity) {
        if (!TransferUi.alive(activity)) return;
        RescheduleNativeUi.Page page = null;
        try {
            RescheduleNativeUi ui = new RescheduleNativeUi(activity);
            page = ui.page("拾光课表", () -> {}, () -> {}, true);
            page.save.setVisible(false);
            View searchBar = ui.nativeView("searchview.COUISearchBar");
            EditText search = (EditText) RescheduleNativeUi.call(searchBar, "getSearchEditText");
            search.setHint("搜索学校或教务系统");
            search.setFocusable(true);
            search.setFocusableInTouchMode(true);
            int searchIcon = ui.id("drawable", "menu_ic_search");
            if (searchIcon != 0)
                RescheduleNativeUi.call(searchBar, "setSearchViewIcon", ui.context.getDrawable(searchIcon));
            Runnable activateSearch = () -> {
                try {
                    if (((Number) RescheduleNativeUi.call(searchBar, "getSearchState")).intValue() != 1)
                        RescheduleNativeUi.call(searchBar, "changeStateImmediately", 1);
                    else {
                        search.requestFocus();
                        Object imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                        if (imm instanceof android.view.inputmethod.InputMethodManager)
                            ((android.view.inputmethod.InputMethodManager) imm).showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception e) { TransferUi.failed(activity, e); }
            };
            searchBar.setOnClickListener(v -> activateSearch.run());
            search.setOnClickListener(v -> activateSearch.run());
            View cancel = (View) RescheduleNativeUi.call(searchBar, "getFunctionalButton");
            if (cancel != null) cancel.setOnClickListener(v -> {
                search.setText("");
                try { RescheduleNativeUi.call(searchBar, "changeStateImmediately", 0); }
                catch (Exception e) { TransferUi.failed(activity, e); }
            });
            LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, ui.dim("coui_search_view_height", 52));
            field.setMargins(ui.space(16), ui.space(8), ui.space(16), ui.space(8));
            page.body.addView(searchBar, field);
            TextView state = TransferUi.caption(ui, "正在读取学校列表…");
            page.body.addView(state);
            ListView list = new ListView(ui.context);
            list.setDivider(null);
            list.setClipToPadding(true);
            final RescheduleNativeUi.Page screen = page;
            LinearLayout content = ui.card();
            content.addView(list, new LinearLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
            lp.setMargins(ui.space(16), 0, ui.space(16), ui.space(12));
            page.body.addView(content, lp);
            page.body.setFocusableInTouchMode(true); page.body.requestFocus();
            new Thread(() -> {
                try {
                    List<ShiguangCatalog.Adapter> all = ShiguangCatalog.load();
                    activity.runOnUiThread(() -> {
                        if (!visible(activity, screen)) return;
                        Schools rows = new Schools(ui, all);
                        list.setAdapter(rows);
                        java.util.function.Consumer<String> filter = query -> {
                            rows.filter(query); list.setSelection(0);
                            state.setText(rows.getCount() == 0 ? "没有找到匹配的学校或教务系统"
                                    : "教务系统 · " + rows.getCount() + " 项");
                        };
                        TransferUi.watch(search, filter); filter.accept(search.getText().toString());
                        list.setOnItemClickListener((parent, v, pos, id) -> {
                            ShiguangCatalog.Adapter selected = rows.getItem(pos);
                            list.setEnabled(false);
                            new Thread(() -> {
                                try {
                                    Source source = new Source(selected);
                                    activity.runOnUiThread(() -> {
                                        list.setEnabled(true);
                                        if (visible(activity, screen)) JwWebView.open(activity, source, screen.dialog::dismiss);
                                    });
                                } catch (Exception e) {
                                    activity.runOnUiThread(() -> { list.setEnabled(true); if (visible(activity, screen)) state.setText(e.getMessage()); });
                                }
                            }, "course-shiguang-adapter").start();
                        });
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> { if (visible(activity, screen)) state.setText("无法读取拾光列表：" + e.getMessage()); });
                }
            }, "course-shiguang-schools").start();
        } catch (Exception e) { if (page != null) page.dialog.dismiss(); TransferUi.failed(activity, e); }
    }
    private static boolean visible(Activity a, RescheduleNativeUi.Page page) {
        return TransferUi.alive(a) && page.dialog.isShowing();
    }
    private static final class Schools extends BaseAdapter {
        final RescheduleNativeUi ui;
        final List<ShiguangCatalog.Adapter> all, matches = new ArrayList<>();
        Schools(RescheduleNativeUi ui, List<ShiguangCatalog.Adapter> all) { this.ui = ui; this.all = all; }
        void filter(String input) {
            String query = input.trim().toLowerCase(Locale.ROOT);
            matches.clear();
            for (ShiguangCatalog.Adapter a : all)
                if ((a.school + " " + a.name + " " + a.id + " " + a.initial + " " + a.url).toLowerCase(Locale.ROOT).contains(query)) matches.add(a);
            notifyDataSetChanged();
        }
        public int getCount() { return matches.size(); }
        public ShiguangCatalog.Adapter getItem(int pos) { return matches.get(pos); }
        public long getItemId(int pos) { return pos; }
        public View getView(int pos, View view, ViewGroup parent) {
            if (view == null) {
                try { view = TransferUi.row(ui, "", null).view; }
                catch (Exception e) { throw new IllegalStateException("宿主缺少课表选项布局", e); }
                view.findViewById(android.R.id.widget_frame).setVisibility(View.GONE);
                view.setClickable(false); view.setFocusable(false);
                ((TextView)view.findViewById(android.R.id.title)).setSingleLine(false);
            }
            ShiguangCatalog.Adapter a = getItem(pos);
            ((TextView)view.findViewById(android.R.id.title)).setText(a.name);
            TextView desc = view.findViewById(android.R.id.summary);
            if (desc != null) {
                desc.setVisibility(View.VISIBLE); desc.setSingleLine(false); desc.setMaxLines(3);
                desc.setText(a.description.isEmpty() ? a.school : a.description);
            }
            return view;
        }
    }
}
