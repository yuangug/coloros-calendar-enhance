package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Calendar's own panel, preference rows, toolbar, editors and material presets. */
final class TransferUi {
    private TransferUi() {}
    static boolean alive(Activity a) { return a != null && !a.isFinishing() && !a.isDestroyed(); }
    static void failed(Activity a, Exception e) {
        if (alive(a)) CurriculumIo.toast(a, e.getMessage() == null ? "无法打开日历界面" : e.getMessage());
    }
    /** A native preference row inside one shared panel/card, not a separate card per item. */
    static RescheduleNativeUi.Row row(RescheduleNativeUi ui, String title, Runnable click) throws Exception {
        RescheduleNativeUi.Row row = ui.row(title, "", click);
        RescheduleNativeUi.call(row.view, "setMarginHorizontal", 0);
        RescheduleNativeUi.call(row.view, "setPositionInGroup", 2);
        RescheduleNativeUi.call(row.view, "refreshCardBg", Color.TRANSPARENT);
        row.view.setPaddingRelative(ui.space(24), row.view.getPaddingTop(), ui.space(24), row.view.getPaddingBottom());
        TextView label = row.view.findViewById(android.R.id.title);
        label.setSingleLine(false); label.setMaxLines(4);
        row.view.findViewById(android.R.id.widget_frame).setVisibility(View.GONE);
        return row;
    }
    static void watch(EditText input, Consumer<String> change) {
        input.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) { change.accept(s.toString()); }
            public void beforeTextChanged(CharSequence s,int a,int c,int f) {}
            public void onTextChanged(CharSequence s,int a,int b,int c) {}
        });
    }
    static View input(RescheduleNativeUi ui, String title, String value) throws Exception {
        // COUIInputView applies styles to its Context; isolate them from the page.
        View field=(View)Class.forName("com.coui.appcompat.edittext.COUIInputView",false,ui.activity.getClassLoader())
                .getConstructor(Context.class).newInstance(new ContextThemeWrapper(ui.context,ui.pageTheme));
        RescheduleNativeUi.call(field,"setTitle",title);
        RescheduleNativeUi.call(field,"setHint","");
        EditText edit=(EditText)RescheduleNativeUi.call(field,"getEditText");
        edit.setSingleLine(true); edit.setContentDescription(title); edit.setText(value);
        return field;
    }
    private static LinearLayout scroll(RescheduleNativeUi ui, LinearLayout contents) {
        LinearLayout outer=ui.column(); ScrollView view=new ScrollView(ui.context) {
            @Override protected void onMeasure(int w,int h) {
                int max=ui.activity.getWindow().getDecorView().getHeight();
                int cap=max>0 ? max*55/100 : ui.dp(360);
                super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.min(cap,MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED?cap:MeasureSpec.getSize(h)),MeasureSpec.AT_MOST));
            }
        };
        view.addView(contents); outer.addView(view); return outer;
    }
    static Dialog choose(Activity a, String title, List<String> options, int selected, Consumer<Integer> cb) throws Exception {
        return choose(a, title, "", options, selected, cb);
    }
    static Dialog choose(Activity a, String title, String description, List<String> options, int selected, Consumer<Integer> cb) throws Exception {
        RescheduleNativeUi ui=new RescheduleNativeUi(a);
        LinearLayout list=ui.column();
        if (!description.isEmpty()) list.addView(caption(ui, description));
        Dialog[] dialog={null}; boolean[] done={false};
        for(int i=0;i<options.size();i++) {
            int index=i;
            RescheduleNativeUi.Row row=row(ui, options.get(i), ()->{
                if(done[0])return; done[0]=true; dialog[0].dismiss(); cb.accept(index);
            });
            if(i==selected) { ViewGroup field=row.view.findViewById(android.R.id.widget_frame); field.setVisibility(View.VISIBLE); field.removeAllViews(); field.addView(ui.check(true)); }
            list.addView(row.view);
        }
        dialog[0]=ui.sheet(title,scroll(ui,list),null,null,()->{if(!done[0])cb.accept(null);},true);
        return dialog[0];
    }
    static TextView caption(RescheduleNativeUi ui, String message) {
        TextView view = ui.text(message, 14, true);
        view.setPaddingRelative(ui.space(24), ui.space(8), ui.space(24), ui.space(8));
        return view;
    }
    static void formAction(Activity a, String title, List<String[]> fields, Map<String,String> initial,
                           Consumer<Map<String,String>> callback) {
        if (!alive(a)) return;
        try { form(a, title, fields, initial, values -> { if (values != null) callback.accept(values); }); }
        catch (Exception e) { failed(a, e); }
    }
    static Dialog form(Activity a,String title,List<String[]> fields,Map<String,String> initial,
                       Consumer<Map<String,String>> callback) throws Exception {
        RescheduleNativeUi ui=new RescheduleNativeUi(a);
        LinearLayout body=ui.column(); body.setPadding(ui.space(24),0,ui.space(24),ui.space(12));
        body.setFocusableInTouchMode(true);
        List<EditText> editors=new ArrayList<>();
        for(String[] f:fields) {
            View field=input(ui,f[1],initial==null?"":initial.getOrDefault(f[0],""));
            EditText edit=(EditText)RescheduleNativeUi.call(field,"getEditText");
            if(f.length>2 && "secret".equals(f[2])) {
                RescheduleNativeUi.call(field,"setPasswordType",1);
                RescheduleNativeUi.call(field,"setEnablePassword",true);
            }
            if("html".equals(f[0])) { edit.setSingleLine(false); edit.setMinLines(4); edit.setMaxLines(8); }
            body.addView(field,new LinearLayout.LayoutParams(-1,-2)); editors.add(edit);
        }
        Dialog[] dialog={null}; boolean[] ok={false};
        Runnable confirm=()->{
            Map<String,String> values=new LinkedHashMap<>();
            for(int i=0;i<fields.size();i++)values.put(fields.get(i)[0],editors.get(i).getText().toString());
            ok[0]=true;dialog[0].dismiss();callback.accept(values);
        };
        Runnable dismissed=()->{if(!ok[0])callback.accept(null);};
        if(fields.size()>3) {
            RescheduleNativeUi.Page page=ui.page(title,confirm,dismissed,true);
            dialog[0]=page.dialog;
            ScrollView scroll=new ScrollView(ui.context); scroll.setFillViewport(false);
            LinearLayout card=ui.card(); card.addView(body);
            LinearLayout content=ui.column(); content.addView(card); scroll.addView(content);
            page.body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        } else dialog[0]=ui.sheet(title,scroll(ui,body),"确定",confirm,dismissed,true);
        body.requestFocus();
        return dialog[0];
    }
    static Dialog alert(Activity a,String title,String message,String confirm,Consumer<Boolean> callback) throws Exception {
        RescheduleNativeUi ui=new RescheduleNativeUi(a); LinearLayout body=ui.column();
        TextView text=ui.text(message,16,false);text.setPadding(ui.space(24),ui.space(8),ui.space(24),ui.space(12));body.addView(text);
        Dialog[] dialog={null};boolean[] ok={false};
        dialog[0]=ui.sheet(title,scroll(ui,body),confirm,()->{ok[0]=true;dialog[0].dismiss();},()->callback.accept(ok[0]),true);
        return dialog[0];
    }
}
