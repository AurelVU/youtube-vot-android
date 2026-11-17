package io.github.aurelvu.vot;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import app.revanced.extension.youtube.patches.VideoInformation;

/** Owns all playback state on Android's main thread; network work never blocks it. */
public final class VotController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool();
    private static final VotSync SYNC = new VotSync();
    private static WeakReference<View> button = new WeakReference<>(null);
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static WeakReference<ViewGroup> overlay = new WeakReference<>(null);
    private static TextView subtitle;
    private static MediaPlayer audio;
    private static VotClient client, subtitleClient;
    private static boolean enabled, prepared, buffering, seeking, nativePlaying, audioProgress;
    private static long generation, lastAudioPosition = -1, seekStarted;
    private static float appliedRate = -1;
    private static double activeDuration;
    private static String activeVideo = "", audioUrl, lastLabel = "";
    private static List<VotClient.Cue> cues = Collections.emptyList();
    private static final Runnable TICK = () -> { tick(); };

    private VotController() {}
    private static double now() { return SystemClock.elapsedRealtimeNanos() / 1e9; }
    public static void attach(View view) {
        MAIN.post(() -> {
            button = new WeakReference<>(view);
            Activity owner = findActivity(view.getContext());
            if (owner != null) activity = new WeakReference<>(owner);
            if (view.getParent() instanceof View) findOverlay((View)view.getParent());
            updateLabel(false);
        });
    }
    static Activity findActivity(Context context) {
        for (int i=0; context instanceof ContextWrapper && i<16; i++) {
            if(context instanceof Activity) return (Activity)context;
            Context next=((ContextWrapper)context).getBaseContext();
            if(next==context)break;
            context=next;
        }
        return context instanceof Activity ? (Activity)context : null;
    }
    private static void findOverlay(View start) {
        ViewGroup candidate=null;
        for(View v=start; v!=null;) {
            if(v instanceof ViewGroup && v.getHeight()>dp(v.getContext(),140)) {
                if(candidate==null)candidate=(ViewGroup)v;
                try {
                    String name=v.getResources().getResourceEntryName(v.getId());
                    if(name.equals("player_overlays") || name.equals("player_view")) {
                        candidate=(ViewGroup)v; break;
                    }
                } catch(RuntimeException ignored) {}
            }
            v=v.getParent() instanceof View ? (View)v.getParent() : null;
        }
        if(candidate!=null && candidate!=overlay.get()) {
            clearSubtitle(); overlay=new WeakReference<>(candidate);
        }
    }
    public static void toggle(View view) {
        MAIN.post(() -> { attach(view); if(enabled)stop(true);else start(); });
    }
    public static void showSettings(View view) {
        MAIN.post(() -> { Activity owner=findActivity(view.getContext()); if(owner!=null)VotSettings.show(owner); });
    }
    static SharedPreferences preferences() {
        Activity a=activity.get();
        if(a==null)throw new IllegalStateException("Откройте плеер видео.");
        return a.getSharedPreferences("YouTubeVOT",Context.MODE_PRIVATE);
    }
    static void settingsChanged(boolean restart) {
        MAIN.post(() -> {
            VotOriginalAudio.setOriginalFraction(preferences().getFloat("original",0.2f));
            if(audio!=null && prepared) {
                float v=preferences().getFloat("translation",1f);
                try {audio.setVolume(v,v);} catch(IllegalStateException ignored) {}
            }
            if(restart && enabled) { stop(false); start(); }
            else if(enabled && preferences().getBoolean("subtitles",false)) {
                if(prepared && subtitleClient==null)requestSubtitles(generation);
            } else {
                if(subtitleClient!=null)subtitleClient.cancel();subtitleClient=null;
                cues=Collections.emptyList();clearSubtitle();
            }
            updateLabel(false);
        });
    }
    private static void start() {
        String id=VideoInformation.getVideoId();
        double length=VideoInformation.getVideoLength()/1000.0;
        if(id==null || !id.matches("[A-Za-z0-9_-]{11}") || length<=0 || length>14400 || VideoInformation.lastVideoIdIsShort()) {
            toast("Откройте обычное видео длительностью до четырёх часов."); return;
        }
        enabled=true; prepared=false; buffering=false; seeking=false; audioProgress=false;
        activeVideo=id;activeDuration=length;cues=Collections.emptyList();SYNC.reset();
        final long token=++generation;
        client=new VotClient();
        updateLabel(true); MAIN.removeCallbacks(TICK); MAIN.post(TICK);
        requestTranslation(token,true);
    }
    private static void requestTranslation(long token,boolean first) {
        if(!current(token))return;
        SharedPreferences p=preferences();
        String id=activeVideo, source=p.getString("source","en"), target=p.getString("target","ru");
        boolean lively=p.getBoolean("lively",false);
        String cookie=lively?VotSettings.sessionCookie():null;
        double duration=activeDuration;
        VotClient requester=client;
        NETWORK.execute(() -> {
            try {
                VotProtocol.Translation result=requester.translate(id,duration,source,target,lively,cookie,first);
                MAIN.post(() -> {
                    if(!current(token))return;
                    if(result.status==1 || result.status==5) {
                        if(lively && !result.lively) { fail("Сервис не предоставил живую озвучку. Выберите обычные голоса или войдите в Яндекс.");return; }
                        prepareAudio(result.url,token);
                        if(preferences().getBoolean("subtitles",false))requestSubtitles(token);
                    } else if(result.status==7) {
                        fail("Для живых голосов войдите в аккаунт Яндекса через настройки перевода.");
                    } else if(result.status==0) {
                        fail("Сервис не смог перевести это видео.");
                    } else {
                        int delay=Math.max(2,Math.min(15,result.remainingTime));
                        MAIN.postDelayed(() -> requestTranslation(token,false),delay*1000L);
                    }
                });
            } catch(Exception error) {
                MAIN.post(() -> {if(current(token))fail(error.getMessage());});
            }
        });
    }
    private static void prepareAudio(String url,long token) {
        if(!current(token))return;
        releaseAudio(); audioUrl=url;
        MediaPlayer player=new MediaPlayer(); audio=player;
        player.setOnPreparedListener(mp -> {
            if(!current(token)||audio!=mp)return;
            prepared=true; buffering=false;
            float volume=preferences().getFloat("translation",1f);mp.setVolume(volume,volume);
            seek(mp,VideoInformation.getVideoTime()/1000.0);
            updateLabel(false);
        });
        player.setOnSeekCompleteListener(mp -> {if(current(token)&&audio==mp)seeking=false;});
        player.setOnInfoListener((mp,what,extra) -> {
            if(!current(token)||audio!=mp)return false;
            if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START) {buffering=true;VotOriginalAudio.setDucking(false);}
            if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END)buffering=false;
            updateLabel(false); return false;
        });
        player.setOnErrorListener((mp,what,extra) -> {
            if(current(token)&&audio==mp)fail("Не удалось воспроизвести озвучку ("+what+").");return true;
        });
        player.setOnCompletionListener(mp -> {if(current(token)&&audio==mp)stop(false);});
        try {
            // No Yandex headers are attached to media/CDN requests.
            player.setDataSource(url); player.prepareAsync();
        } catch(Exception error) {fail("Не удалось подготовить озвучку.");}
    }
    private static void requestSubtitles(long token) {
        if(!current(token)||!preferences().getBoolean("subtitles",false))return;
        if(subtitleClient!=null)subtitleClient.cancel();
        VotClient requester=new VotClient(); subtitleClient=requester;
        String id=activeVideo,source=preferences().getString("source","en"),target=preferences().getString("target","ru");
        fetchSubtitles(token,requester,id,source,target,0);
    }
    private static void fetchSubtitles(long token,VotClient requester,String id,String source,String target,int attempt) {
        if(!current(token)||subtitleClient!=requester||!preferences().getBoolean("subtitles",false))return;
        NETWORK.execute(() -> {
            try {
                List<VotClient.Cue> result=requester.subtitles(id,source,target);
                MAIN.post(() -> {if(current(token)&&subtitleClient==requester)cues=result;});
            } catch(VotClient.SubtitlesPendingException waiting) {
                if(attempt<12)MAIN.postDelayed(() -> fetchSubtitles(token,requester,id,source,target,attempt+1),15000);
            } catch(Exception ignored) { /* Subtitles must not interrupt audio playback. */ }
        });
    }
    public static void onTime(long milliseconds) {
        MAIN.post(() -> {if(enabled)SYNC.observe(milliseconds/1000.0,now(),VideoInformation.getPlaybackSpeed());});
    }
    public static void onSpeed(float rate) {
        MAIN.post(() -> {if(enabled) {appliedRate=-1;SYNC.observe(VideoInformation.getVideoTime()/1000.0,now(),rate);}});
    }
    public static void onVideoId(String id) {
        MAIN.post(() -> {if(enabled&&!activeVideo.equals(id))stop(false);});
    }
    public static void onVideoState(Enum<?> state) {
        String name=state==null?"":state.name();
        MAIN.post(() -> {
            nativePlaying=name.equals("PLAYING");
            if(!nativePlaying && audio!=null&&prepared) {
                try {if(audio.isPlaying())audio.pause();}catch(IllegalStateException ignored){}
                VotOriginalAudio.setDucking(false); updateLabel(false);
            }
            if(enabled && (name.equals("ENDED")||name.equals("UNRECOVERABLE_ERROR")))stop(false);
        });
    }
    private static void tick() {
        if(!enabled)return;
        if(!activeVideo.equals(VideoInformation.getVideoId())) {stop(false);return;}
        try {
            float rate=VideoInformation.getPlaybackSpeed();
            boolean moving=SYNC.observe(VideoInformation.getVideoTime()/1000.0,now(),rate);
            if(audio!=null&&prepared) {
                if(seeking&&SystemClock.elapsedRealtime()-seekStarted>2500)seeking=false;
                long position=audio.getCurrentPosition();
                VotSync.Decision d=SYNC.decide(SYNC.projectedTime(now(),rate),position/1000.0,
                        rate,nativePlaying&&moving,!buffering&&!seeking,now());
                if(d.seek)seek(audio,d.target);
                if(!d.play||buffering||seeking) {
                    if(audio.isPlaying())audio.pause();
                    audioProgress=false;VotOriginalAudio.setDucking(false);
                } else {
                    if(Math.abs(appliedRate-d.rate)>0.002f) {
                        audio.setPlaybackParams(new PlaybackParams().setSpeed(d.rate).setPitch(1f)); appliedRate=d.rate;
                    }
                    if(!audio.isPlaying())audio.start();
                    audioProgress=lastAudioPosition>=0&&position>lastAudioPosition&&position-lastAudioPosition<2000;
                    VotOriginalAudio.setOriginalFraction(preferences().getFloat("original",0.2f));
                    VotOriginalAudio.setDucking(audioProgress);
                }
                lastAudioPosition=position;
                renderSubtitles(SYNC.projectedTime(now(),rate));
            }
            updateLabel(false);
        } catch(RuntimeException error) {fail("Ошибка воспроизведения озвучки.");return;}
        MAIN.postDelayed(TICK,100);
    }
    private static void seek(MediaPlayer player,double seconds) {
        if(!Double.isFinite(seconds)||seconds<0)return;
        seeking=true; seekStarted=SystemClock.elapsedRealtime();audioProgress=false;
        player.seekTo((long)(seconds*1000),MediaPlayer.SEEK_CLOSEST);
    }
    private static boolean current(long token) {return enabled&&generation==token;}
    private static void fail(String message) {
        stop(false); toast(message==null?"Ошибка сервиса перевода.":message);
    }
    private static void stop(boolean notify) {
        enabled=false;++generation; MAIN.removeCallbacks(TICK);
        if(client!=null)client.cancel();if(subtitleClient!=null)subtitleClient.cancel();
        client=subtitleClient=null;releaseAudio();audioUrl=null;cues=Collections.emptyList();
        SYNC.reset();clearSubtitle();VotOriginalAudio.restoreAll();updateLabel(false);
        if(notify)toast("Перевод выключен");
    }
    private static void releaseAudio() {
        MediaPlayer previous=audio;audio=null;prepared=false;buffering=false;seeking=false;
        audioProgress=false;lastAudioPosition=-1;appliedRate=-1;VotOriginalAudio.setDucking(false);
        if(previous!=null) {try {previous.release();}catch(RuntimeException ignored){}}
    }
    private static void updateLabel(boolean notify) {
        String state=VotPlaybackState.label(enabled,prepared,buffering,audioProgress,nativePlaying);
        Activity owner=activity.get();
        SharedPreferences prefs=owner==null?null:owner.getSharedPreferences("YouTubeVOT",Context.MODE_PRIVATE);
        String target=prefs==null?"ru":prefs.getString("target","ru");
        boolean lively=prefs!=null&&prefs.getBoolean("lively",false);
        String label=VotControlsUi.title(state,enabled,audio!=null,lively,target);
        View view=button.get();
        if(view!=null) {
            String action=enabled?(audio!=null?"Выключить перевод":"Отменить перевод"):label;
            view.setContentDescription(action+". "+(enabled?label+". ":"")+"Удерживайте для настроек.");
            view.setTooltipText(label);
            int color=VotControlsUi.color(enabled,state);
            if(view instanceof TextView) {((TextView)view).setText(label);((TextView)view).setTextColor(color);}
            else if(view instanceof ImageView)((ImageView)view).setColorFilter(color);
        }
        VotPlayerButton.refreshLayout();
        if(!label.equals(lastLabel) && (notify||state.equals("Перевод включён")))toast(label);
        lastLabel=label;
    }
    private static void renderSubtitles(double seconds) {
        if(!preferences().getBoolean("subtitles",false)) {clearSubtitle();return;}
        View view=button.get(); if(view==null)return;
        ViewGroup parent=overlay.get();
        if(parent==null||!parent.isAttachedToWindow()){findOverlay(view);parent=overlay.get();}
        if(parent==null||parent.getWidth()==0)return;
        String text="";
        int low=0,high=cues.size();
        while(low<high){int mid=(low+high)>>>1;if(cues.get(mid).start<=seconds)low=mid+1;else high=mid;}
        if(low>0&&seconds<cues.get(low-1).end)text=cues.get(low-1).text;
        if(text.isEmpty()){if(subtitle!=null)subtitle.setVisibility(View.GONE);return;}
        if(subtitle==null) {
            subtitle=new TextView(parent.getContext());subtitle.setTextColor(Color.WHITE);subtitle.setTextSize(16);
            subtitle.setGravity(Gravity.CENTER);subtitle.setBackgroundColor(0xBB161616);subtitle.setPadding(dp(parent.getContext(),8),dp(parent.getContext(),4),dp(parent.getContext(),8),dp(parent.getContext(),4));
            subtitle.setMaxLines(4);parent.getOverlay().add(subtitle);
        }
        subtitle.setText(text);subtitle.setVisibility(View.VISIBLE);
        int inset=dp(parent.getContext(),12),width=parent.getWidth()-inset*2;
        subtitle.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(parent.getHeight(),View.MeasureSpec.AT_MOST));
        int bottom=parent.getHeight()-dp(parent.getContext(),28);
        subtitle.layout(inset,Math.max(0,bottom-subtitle.getMeasuredHeight()),inset+width,bottom);
    }
    private static void clearSubtitle() {
        ViewGroup parent=overlay.get();if(subtitle!=null&&parent!=null)parent.getOverlay().remove(subtitle);
        subtitle=null;
    }
    static void exportAudio() {
        Activity a=activity.get();if(a==null||audioUrl==null){toast("Сначала дождитесь готовности озвучки.");return;}
        try {
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(audioUrl));
            request.setTitle("Перевод видео").setMimeType("audio/mpeg").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if(Build.VERSION.SDK_INT>=29)request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"VOT-"+activeVideo+".mp3");
            else request.setDestinationInExternalFilesDir(a,Environment.DIRECTORY_DOWNLOADS,"VOT-"+activeVideo+".mp3");
            ((DownloadManager)a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);toast("Озвучка добавлена в загрузки");
        } catch(RuntimeException error){toast("Не удалось начать скачивание озвучки.");}
    }
    static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static void toast(String text){Activity a=activity.get();if(a!=null)Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
}
