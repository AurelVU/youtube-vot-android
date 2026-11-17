package io.github.aurelvu.vot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.revanced.extension.youtube.patches.VideoInformation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Native visibility hooks drive a small HUD inside YouTube's own player overlay. */
public final class VotPlayerButton {
    private static final String HUD_TAG="io.github.aurelvu.vot.player_controls";
    private static final Method PLAYER_TYPE=staticGetter("app.revanced.extension.youtube.shared.PlayerType");
    private static final Method CONTROLS_VISIBILITY=staticGetter("app.revanced.extension.youtube.shared.PlayerControlsVisibility");
    private static WeakReference<Hud> current=new WeakReference<>(null);
    private static boolean overlayVisible=true;

    private VotPlayerButton() {}

    public static void initializeButton(View controlsView) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {controlsView.post(()->initializeButton(controlsView));return;}
        // A single deferred attempt handles inflation before insertion without adding listeners.
        if(!install(controlsView))controlsView.post(()->install(controlsView));
    }
    private static boolean install(View controlsView) {
        FrameLayout host=findPlayerHost(controlsView);
        if(host==null)return false;
        Hud old=current.get(), hud=null;
        for(int i=0;i<host.getChildCount();i++) {
            View child=host.getChildAt(i);
            if(HUD_TAG.equals(child.getTag())&&child instanceof Hud) {hud=(Hud)child;break;}
        }
        if(old!=null&&old!=hud)old.dispose();
        if(hud==null) {
            hud=new Hud(host);
            host.addView(hud,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(host,44),Gravity.TOP|Gravity.LEFT));
        }
        current=new WeakReference<>(hud);
        hud.raiseAboveNativeChrome();hud.refresh(false);VotController.attach(hud.translate);
        return true;
    }

    /** The identified native custom-views frame is the only insertion point. */
    private static FrameLayout findPlayerHost(View start) {
        for(View node=start;node!=null;node=node.getParent() instanceof View?(View)node.getParent():null) {
            String name=resourceName(node);
            if(node instanceof FrameLayout&&name.equals("player_overlays_custom_views_container"))return (FrameLayout)node;
            if(name.equals("player_overlays")||node.getClass().getName().endsWith(".YouTubePlayerOverlaysLayout")) {
                if(node instanceof ViewGroup) {
                    ViewGroup overlay=(ViewGroup)node;
                    for(int i=0;i<overlay.getChildCount();i++) {
                        View child=overlay.getChildAt(i);
                        if(child instanceof FrameLayout&&resourceName(child).equals("player_overlays_custom_views_container"))
                            return (FrameLayout)child;
                    }
                }
                // Never continue out of this player to an Activity/DecorView fallback.
                return null;
            }
        }
        return null;
    }
    private static String resourceName(View view) {
        try{return view.getResources().getResourceEntryName(view.getId());}
        catch(RuntimeException ignored){return "";}
    }
    private static Method staticGetter(String className) {
        try{return Class.forName(className).getMethod("getCurrent");}
        catch(ReflectiveOperationException|LinkageError error){Log.w("YouTubeVOT","Native player state unavailable");return null;}
    }
    private static String nativeState(Method getter) {
        if(getter==null)return "";
        try {Object value=getter.invoke(null);return value instanceof Enum<?>?((Enum<?>)value).name():"";}
        catch(ReflectiveOperationException|RuntimeException error){return "";}
    }
    private static boolean largePlayer() {
        String type=nativeState(PLAYER_TYPE);
        return type.equals("WATCH_WHILE_MAXIMIZED")||type.equals("WATCH_WHILE_FULLSCREEN");
    }
    private static int dp(View view,int value) {
        return Math.round(value*view.getResources().getDisplayMetrics().density);
    }

    public static void setVisibility(boolean visible,boolean animated) {
        // Same native special case as ReVanced's PlayerControlButton while seeking.
        if(visible&&!animated)return;
        overlayVisible=visible;
        Hud hud=current.get();if(hud!=null)hud.refresh(animated);
    }
    public static void setVisibilityImmediate(boolean visible) {
        overlayVisible=visible;
        Hud hud=current.get();
        if(hud!=null) {
            if(visible){hud.removeCallbacks(hud.deferredRefresh);hud.post(hud.deferredRefresh);}
            else hud.refresh(false);
        }
    }
    public static void setVisibilityNegatedImmediate() {
        if(!nativeState(CONTROLS_VISIBILITY).equals("PLAYER_CONTROLS_VISIBILITY_HIDDEN"))return;
        overlayVisible=false;
        Hud hud=current.get();if(hud!=null)hud.refresh(true);
    }
    /** Updates player type without installing global observers. */
    static void refreshLayout() {
        Hud hud=current.get();if(hud!=null)hud.refresh(false);
    }

    private static final class ActionText extends TextView {
        ActionText(Context context){super(context);}
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
    }
    private static final class Hud extends LinearLayout implements View.OnLayoutChangeListener {
        private final WeakReference<FrameLayout> host;
        private final ActionText translate,settings;
        private final Runnable deferredRefresh=()->refresh(false);
        private boolean listening,shown;

        Hud(FrameLayout nativeHost) {
            super(nativeHost.getContext());host=new WeakReference<>(nativeHost);
            setTag(HUD_TAG);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);setVisibility(GONE);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            translate=action(false);translate.setText("Перевести на русский");
            translate.setContentDescription("Перевести на русский. Удерживайте для настроек.");
            translate.setOnClickListener(VotController::toggle);
            translate.setOnLongClickListener(view->{VotController.showSettings(view);return true;});
            settings=action(true);settings.setText("⋯");settings.setContentDescription("Настройки перевода");
            settings.setTooltipText("Настройки перевода");settings.setOnClickListener(VotController::showSettings);
            addView(translate,new LinearLayout.LayoutParams(dp(this,176),dp(this,44)));
            LinearLayout.LayoutParams more=new LinearLayout.LayoutParams(dp(this,44),dp(this,44));
            more.leftMargin=dp(this,6);addView(settings,more);
        }
        private ActionText action(boolean ellipsis) {
            ActionText view=new ActionText(getContext());
            view.setGravity(Gravity.CENTER);view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);
            view.setIncludeFontPadding(false);view.setTextColor(0xFFFFFFFF);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP,ellipsis?24:13);
            view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            view.setPadding(ellipsis?0:dp(this,10),0,ellipsis?0:dp(this,10),0);
            view.setFocusable(true);view.setClickable(true);view.setMinimumHeight(dp(this,44));
            GradientDrawable shape=new GradientDrawable();shape.setColor(0xB3000000);
            shape.setCornerRadius(dp(this,ellipsis?22:20));
            shape.setStroke(Math.max(1,Math.round(getResources().getDisplayMetrics().density*0.5f)),0x40FFFFFF);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),shape,null));
            return view;
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();FrameLayout parent=host.get();
            Hud previous=current.get();
            if(previous!=null&&previous!=this)previous.dispose();
            current=new WeakReference<>(this);
            if(parent!=null&&!listening){parent.addOnLayoutChangeListener(this);listening=true;}
            requestApplyInsets();refresh(false);
        }
        @Override protected void onDetachedFromWindow() {
            releaseListener();animate().cancel();removeCallbacks(deferredRefresh);
            shown=false;setAlpha(1f);setVisibility(GONE);
            if(current.get()==this)current=new WeakReference<>(null);
            super.onDetachedFromWindow();
        }
        private void releaseListener() {
            FrameLayout parent=host.get();
            if(parent!=null&&listening)parent.removeOnLayoutChangeListener(this);
            listening=false;
        }
        void dispose() {
            releaseListener();animate().cancel();removeCallbacks(deferredRefresh);
            if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);
        }
        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            WindowInsets result=super.onApplyWindowInsets(insets);refresh(false);return result;
        }
        @Override public void onLayoutChange(View view,int left,int top,int right,int bottom,
                                             int oldLeft,int oldTop,int oldRight,int oldBottom) {refresh(false);}
        void raiseAboveNativeChrome() {
            FrameLayout parent=host.get();if(parent==null)return;
            bringToFront();
            // Outside our two hit targets this native FrameLayout stays non-clickable,
            // so regular gestures still fall through to the rest of the player controls.
            if(parent.getParent() instanceof ViewGroup)parent.bringToFront();
        }
        @SuppressWarnings("deprecation")
        void refresh(boolean animated) {
            FrameLayout parent=host.get();if(parent==null)return;
            int left=0,top=0,right=0,bottom=0;
            WindowInsets insets=parent.getRootWindowInsets();
            if(insets!=null) {
                int[] location=new int[2];parent.getLocationInWindow(location);
                View window=parent.getRootView();
                // Convert only intersecting window-safe regions to native-player coordinates.
                left=Math.max(0,insets.getSystemWindowInsetLeft()-location[0]);
                top=Math.max(0,insets.getSystemWindowInsetTop()-location[1]);
                right=Math.max(0,location[0]+parent.getWidth()-window.getWidth()+insets.getSystemWindowInsetRight());
                bottom=Math.max(0,location[1]+parent.getHeight()-window.getHeight()+insets.getSystemWindowInsetBottom());
            }
            VotControlsUi.Placement position=VotControlsUi.place(parent.getWidth(),parent.getHeight(),
                    getResources().getDisplayMetrics().density,left,top,right,bottom,
                    largePlayer(),VideoInformation.lastVideoIdIsShort());
            FrameLayout.LayoutParams group=(FrameLayout.LayoutParams)getLayoutParams();
            if(group!=null&&(group.leftMargin!=position.left||group.topMargin!=position.top
                    ||group.width!=position.pillWidth+position.gap+position.settingsWidth||group.height!=position.height)) {
                group.leftMargin=position.left;group.topMargin=position.top;
                group.width=position.pillWidth+position.gap+position.settingsWidth;group.height=position.height;
                setLayoutParams(group);
            }
            LinearLayout.LayoutParams pill=(LinearLayout.LayoutParams)translate.getLayoutParams();
            if(pill.width!=position.pillWidth||pill.height!=position.height) {
                pill.width=position.pillWidth;pill.height=position.height;translate.setLayoutParams(pill);
            }
            boolean visible=overlayVisible&&position.visible;
            if(visible==shown)return;
            shown=visible;animate().cancel();
            if(visible) {
                raiseAboveNativeChrome();setVisibility(VISIBLE);setAlpha(animated?0f:1f);
                if(animated)animate().alpha(1f).setDuration(150).start();
            } else if(animated&&getVisibility()==VISIBLE) {
                animate().alpha(0f).setDuration(180).withEndAction(()->setVisibility(GONE)).start();
            } else {setAlpha(1f);setVisibility(GONE);}
        }
    }
}
