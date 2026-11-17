package io.github.aurelvu.vot;

/** Platform-independent player-control geometry and iPhone-equivalent presentation. */
public final class VotControlsUi {
    private VotControlsUi() {}
    public static final class Placement {
        public final boolean visible;
        public final int left, top, pillWidth, height, gap, settingsWidth;
        private Placement(boolean visible, int left, int top, int pillWidth, int height, int gap) {
            this.visible=visible;this.left=left;this.top=top;this.pillWidth=pillWidth;
            this.height=height;this.gap=gap;this.settingsWidth=height;
        }
    }
    public static Placement place(int width, int height, float density, int safeLeft, int safeTop,
                                  int safeRight, int safeBottom, boolean largePlayer, boolean shorts) {
        int hit=Math.round(44*density), gap=Math.round(6*density), margin=Math.round(12*density);
        int available=width-safeLeft-safeRight-2*margin-hit-gap;
        int pill=Math.min(Math.round(176*density),available);
        int top=Math.max(Math.round(50*density),safeTop+hit);
        boolean visible=largePlayer&&!shorts&&height>=Math.round(140*density)
                &&pill>=Math.round(130*density)&&top+hit<=height-safeBottom;
        return new Placement(visible,safeLeft+margin,top,Math.max(0,pill),hit,gap);
    }
    public static String title(String state, boolean enabled, boolean hasAudio, boolean lively, String target) {
        if(!enabled) {
            String language="en".equals(target)?"английский":"kk".equals(target)?"казахский":"русский";
            return "Перевести на "+language;
        }
        if(!hasAudio&&"Подготовка озвучки…".equals(state))return "Отмена · готовится…";
        if(lively&&"Перевод включён".equals(state))return "Живые голоса · вкл.";
        return state;
    }
    public static int color(boolean enabled, String state) {
        return !enabled?0xFFFFFFFF:"Перевод включён".equals(state)?0xFFFFD60A:0xFF0A84FF;
    }
}
