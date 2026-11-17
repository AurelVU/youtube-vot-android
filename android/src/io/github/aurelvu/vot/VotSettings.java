package io.github.aurelvu.vot;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Native settings and the first-party Yandex sign-in page. Never persists session values. */
final class VotSettings {
    private static final int BACKGROUND=0xff1c1c1e, CARD=0xff2c2c2e, SECONDARY=0xffa0a0a6;
    private static final int BLUE=0xff0a84ff, GREEN=0xff30d158, DIVIDER=0xff3a3a3c;
    private VotSettings() {}

    static void show(Activity a) {
        SharedPreferences p=VotController.preferences();
        Dialog dialog=sheet(a);
        LinearLayout root=column(a);
        root.setBackground(round(a,BACKGROUND,16));
        root.setClipToOutline(true);
        root.addView(header(a,"Настройки перевода",dialog));
        LinearLayout content=column(a);
        content.setPadding(dp(a,20),dp(a,10),dp(a,20),dp(a,24));
        ScrollView scroll=new ScrollView(a);
        scroll.setFillViewport(false);scroll.setClipToPadding(false);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout languages=section(content,"ЯЗЫКИ");
        language(languages,p,"Оригинал","source",new String[]{"en","de","fr","es","it","ja","ko","zh"},
                new String[]{"Английский","Немецкий","Французский","Испанский","Итальянский","Японский","Корейский","Китайский"},"en");
        divider(languages);
        language(languages,p,"Перевод","target",new String[]{"ru","en","kk"},new String[]{"Русский","Английский","Казахский"},"ru");
        footnote(content,"При смене языка активный перевод будет запрошен заново.");

        LinearLayout voices=section(content,"ОЗВУЧКА");
        TextView[] checks=new TextView[2];
        LinearLayout[] voiceRows=new LinearLayout[2];
        voiceRows[0]=voice(voices,"Обычные голоса","Быстрая закадровая озвучка",checks,0);
        divider(voices);
        voiceRows[1]=voice(voices,"Живые голоса","Голоса, похожие на голоса авторов видео",checks,1);
        Runnable refreshVoices=() -> {
            boolean lively=p.getBoolean("lively",false);
            for(int i=0;i<2;i++) {
                boolean selected=(i==1)==lively;
                checks[i].setVisibility(selected?View.VISIBLE:View.INVISIBLE);
                voiceRows[i].setSelected(selected);
            }
        };
        for(int i=0;i<2;i++) {
            final boolean lively=i==1;
            action(voiceRows[i],v -> {
                if(p.getBoolean("lively",false)!=lively) {
                    p.edit().putBoolean("lively",lively).apply();VotController.settingsChanged(true);
                }
                refreshVoices.run();
            });
        }
        refreshVoices.run();
        footnote(content,"Для живых голосов может потребоваться вход в Яндекс. Доступность зависит от ролика и сервиса.");

        LinearLayout subtitles=section(content,"СУБТИТРЫ");
        Switch toggle=new Switch(a);
        toggle.setText("Показывать субтитры");toggle.setTextColor(Color.WHITE);toggle.setTextSize(17);
        toggle.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));
        toggle.setMinHeight(dp(a,52));toggle.setSwitchPadding(dp(a,12));
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GREEN,0xff636366}));
        toggle.setChecked(p.getBoolean("subtitles",false));
        toggle.setOnCheckedChangeListener((button,checked) -> {
            p.edit().putBoolean("subtitles",checked).apply();VotController.settingsChanged(false);
        });
        subtitles.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
        footnote(content,"Субтитры на языке перевода, если они доступны у сервиса.");

        LinearLayout volumes=section(content,"ГРОМКОСТЬ");
        volume(volumes,p,"Оригинал","original",0.2f);
        divider(volumes);
        volume(volumes,p,"Перевод","translation",1f);

        LinearLayout more=section(content,"ДОПОЛНИТЕЛЬНО");
        LinearLayout download=detailRow(more,"Скачать озвучку","Сохранить готовый аудиофайл");
        accessory(download,"›",SECONDARY);
        action(download,v -> VotController.exportAudio());
        divider(more);
        LinearLayout account=detailRow(more,"Аккаунт Яндекса",sessionCookie()==null?"Войти для живых голосов":"Вход выполнен");
        TextView accountStatus=(TextView)((LinearLayout)account.getChildAt(0)).getChildAt(1);
        accessory(account,"›",SECONDARY);
        action(account,v -> signIn(a,() -> accountStatus.setText(sessionCookie()==null?"Войти для живых голосов":"Вход выполнен")));
        footnote(content,"Перевод выключается при смене видео. Удержание кнопки перевода также открывает настройки.");
        dialog.setContentView(root);
        showSheet(a,dialog,false);
    }

    private static int dp(Context c,int value) { return VotController.dp(c,value); }
    private static LinearLayout column(Context c) {
        LinearLayout view=new LinearLayout(c);view.setOrientation(LinearLayout.VERTICAL);return view;
    }
    private static TextView text(Context c,String value,int size,int color) {
        TextView view=new TextView(c);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;
    }
    private static GradientDrawable round(Context c,int color,int radius) {
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(c,radius));return drawable;
    }
    private static Dialog sheet(Activity a) {
        Dialog dialog=new Dialog(a,android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setCanceledOnTouchOutside(true);return dialog;
    }
    private static void showSheet(Activity a,Dialog dialog,boolean input) {
        dialog.show();
        Window window=dialog.getWindow();
        if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.45f);
        Rect bounds=windowBounds(a);
        int width=Math.max(1,Math.min(dp(a,520),bounds.width()-dp(a,16)));
        window.setLayout(width,Math.max(1,(int)(bounds.height()*0.9f)));
        window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        if(input) {
            // AlertDialog's editor heuristic cannot see HTML inputs. Explicitly keep this
            // dedicated WebView window eligible for IME connections after it is shown.
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
    }
    private static Rect windowBounds(Activity a) {
        if(Build.VERSION.SDK_INT>=30)return a.getWindowManager().getCurrentWindowMetrics().getBounds();
        DisplayMetrics metrics=new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    private static LinearLayout header(Activity a,String title,Dialog dialog) {
        boolean stacked=a.getResources().getConfiguration().fontScale>1.2f;
        LinearLayout header=new LinearLayout(a);
        header.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));
        TextView heading=text(a,title,17,Color.WHITE);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,Typeface.BOLD);
        if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        Button done=new Button(a);
        done.setText("Готово");done.setTextColor(BLUE);done.setTextSize(17);done.setAllCaps(false);
        done.setMinWidth(0);done.setMinimumWidth(0);done.setMinHeight(dp(a,44));
        done.setPadding(dp(a,8),0,dp(a,8),0);done.setBackgroundColor(Color.TRANSPARENT);
        done.setOnClickListener(v -> dialog.dismiss());
        if(stacked) {
            header.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams doneParams=new LinearLayout.LayoutParams(-2,-2);doneParams.gravity=Gravity.END;
            header.addView(done,doneParams);
        } else {
            // Equal side slots keep the title centered independently of the Done label.
            View balance=new View(a);header.addView(balance,new LinearLayout.LayoutParams(dp(a,76),1));
            header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
            header.addView(done,new LinearLayout.LayoutParams(dp(a,76),-2));
        }
        return header;
    }
    private static LinearLayout section(LinearLayout content,String title) {
        Context c=content.getContext();TextView label=text(c,title,13,SECONDARY);
        label.setPadding(dp(c,16),dp(c,22),dp(c,16),dp(c,8));if(Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
        content.addView(label,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout card=column(c);card.setBackground(round(c,CARD,12));card.setClipToOutline(true);
        content.addView(card,new LinearLayout.LayoutParams(-1,-2));return card;
    }
    private static void footnote(LinearLayout content,String value) {
        Context c=content.getContext();TextView label=text(c,value,13,SECONDARY);
        label.setPadding(dp(c,16),dp(c,8),dp(c,16),0);content.addView(label,new LinearLayout.LayoutParams(-1,-2));
    }
    private static void divider(LinearLayout card) {
        View line=new View(card.getContext());line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(card.getContext(),1));
        params.leftMargin=dp(card.getContext(),16);card.addView(line,params);
    }
    private static LinearLayout row(LinearLayout parent) {
        Context c=parent.getContext();LinearLayout row=new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(c,52));
        row.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;
    }
    private static void action(View row,View.OnClickListener listener) {
        StateListDrawable background=new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed},round(row.getContext(),0xff48484a,0));
        background.addState(new int[]{android.R.attr.state_focused},round(row.getContext(),0xff48484a,0));
        background.addState(new int[]{},round(row.getContext(),Color.TRANSPARENT,0));
        row.setBackground(background);row.setOnClickListener(listener);row.setFocusable(true);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());
            }
        });
    }
    private static TextView accessory(LinearLayout row,String value,int color) {
        TextView view=text(row.getContext(),value,24,color);view.setGravity(Gravity.CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(row.getContext(),28),-2);
        params.leftMargin=dp(row.getContext(),8);row.addView(view,params);return view;
    }
    private static LinearLayout detailRow(LinearLayout parent,String title,String subtitle) {
        Context c=parent.getContext();LinearLayout row=row(parent),labels=column(c);
        labels.addView(text(c,title,17,Color.WHITE),new LinearLayout.LayoutParams(-1,-2));
        TextView detail=text(c,subtitle,13,SECONDARY);detail.setPadding(0,dp(c,4),0,0);
        labels.addView(detail,new LinearLayout.LayoutParams(-1,-2));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));return row;
    }
    private static LinearLayout voice(LinearLayout parent,String title,String subtitle,TextView[] checks,int index) {
        LinearLayout row=detailRow(parent,title,subtitle);row.setMinimumHeight(dp(parent.getContext(),76));
        checks[index]=accessory(row,"✓",BLUE);return row;
    }
    private static void language(LinearLayout parent,SharedPreferences p,String title,String key,String[] values,String[] names,String fallback) {
        Context c=parent.getContext();LinearLayout row=row(parent);
        TextView label=text(c,title,17,Color.WHITE),value=text(c,"",17,BLUE);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,0.8f));
        row.addView(value,new LinearLayout.LayoutParams(0,-2,1.2f));
        Runnable refresh=() -> {
            String selected=p.getString(key,fallback);String name=names[0];
            for(int i=0;i<values.length;i++)if(values[i].equals(selected))name=names[i];
            value.setText(name+"  ⌄");row.setContentDescription(title+": "+name);
        };
        refresh.run();
        action(row,v -> {
            int selected=0;
            for(int i=0;i<values.length;i++)if(values[i].equals(p.getString(key,fallback)))selected=i;
            AlertDialog picker=new AlertDialog.Builder(c,android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title).setSingleChoiceItems(names,selected,(dialog,index) -> {
                        if(!values[index].equals(p.getString(key,fallback))) {
                            p.edit().putString(key,values[index]).apply();VotController.settingsChanged(true);
                        }
                        refresh.run();dialog.dismiss();
                    }).setNegativeButton("Отмена",null).create();
            picker.show();
        });
    }
    private static void volume(LinearLayout parent,SharedPreferences p,String title,String key,float fallback) {
        Context c=parent.getContext();LinearLayout group=column(c);
        group.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,8));
        TextView caption=text(c,"",17,Color.WHITE);group.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        SeekBar slider=new SeekBar(c);slider.setMax(100);slider.setMinimumHeight(dp(c,44));
        slider.setProgressTintList(ColorStateList.valueOf(BLUE));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff48484a));
        slider.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        slider.setContentDescription("Громкость: "+title);
        int value=Math.round(p.getFloat(key,fallback)*100);slider.setProgress(value);caption.setText(title+": "+value+"%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar s,int progress,boolean fromUser){
                caption.setText(title+": "+progress+"%");
                if(fromUser){p.edit().putFloat(key,progress/100f).apply();VotController.settingsChanged(false);}
            }
        });
        group.addView(slider,new LinearLayout.LayoutParams(-1,dp(c,44)));parent.addView(group,new LinearLayout.LayoutParams(-1,-2));
    }
    static String sessionCookie() {
        String cookies=CookieManager.getInstance().getCookie("https://yandex.ru");
        if(cookies==null)return null;
        for(String pair:cookies.split(";")) {
            String value=pair.trim();
            if(value.startsWith("Session_id=")&&value.length()>11&&value.length()<16384) {
                for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return null;
                return value;
            }
        }
        return null;
    }
    private static boolean yandexPage(Uri uri) {
        String host=uri.getHost();
        return "https".equals(uri.getScheme())&&host!=null&&(host.equals("yandex.ru")||host.endsWith(".yandex.ru")||host.equals("yandex.com")||host.endsWith(".yandex.com"));
    }
    private static void signIn(Activity a,Runnable refreshAccount) {
        WebView web=new WebView(a);WebSettings settings=web.getSettings();
        web.setFocusable(true);web.setFocusableInTouchMode(true);
        settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                return request.isForMainFrame()&&!yandexPage(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return !yandexPage(Uri.parse(url));}
        });
        Dialog dialog=sheet(a);LinearLayout root=column(a);
        root.setBackground(round(a,BACKGROUND,16));root.setClipToOutline(true);
        root.addView(header(a,"Аккаунт Яндекса",dialog));
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> {
            CookieManager.getInstance().flush();web.stopLoading();web.destroy();
            refreshAccount.run();
            VotController.toast(sessionCookie()==null?"Вход в Яндекс не обнаружен":"Вход в Яндекс сохранён");
            VotController.settingsChanged(true);
        });
        showSheet(a,dialog,true);
        web.requestFocus();
        web.loadUrl("https://passport.yandex.ru/auth?retpath=https%3A%2F%2Fyandex.ru");
    }
}
