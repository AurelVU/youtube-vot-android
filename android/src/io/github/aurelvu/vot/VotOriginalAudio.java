package io.github.aurelvu.vot;

import android.media.AudioTrack;
import android.media.MediaPlayer;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Per-player gain bridge. Only patched calls from the original player enter here. */
@SuppressWarnings("deprecation") // Preserve original AudioTrack.setStereoVolume calls on older players.
public final class VotOriginalAudio {
    private static final Object LOCK = new Object();
    private static final WeakHashMap<Object, Gains> PLAYERS = new WeakHashMap<>();
    private static float originalFraction = 1f;
    private static boolean ducking;

    private VotOriginalAudio() {}

    public static void setOriginalFraction(float fraction) {
        if (!valid(fraction)) throw new IllegalArgumentException("Original fraction must be between 0 and 1");
        synchronized (LOCK) {
            if (originalFraction == fraction) return;
            originalFraction = fraction;
            if (ducking) applyAll();
        }
    }

    public static void setDucking(boolean enabled) {
        synchronized (LOCK) {
            if (ducking == enabled) return;
            ducking = enabled;
            applyAll();
        }
    }

    /** Error, stop and video-change paths restore the latest native requested gains. */
    public static void restoreAll() {
        synchronized (LOCK) {
            ducking = false;
            applyAll();
        }
    }

    public static int setVolume(AudioTrack track, float volume) {
        synchronized (LOCK) {
            // Preserve framework validation and return codes for invalid native arguments.
            if (!valid(volume)) return track.setVolume(volume);
            int result = track.setVolume(volume * factor());
            if (result >= 0) PLAYERS.put(track, new Gains(volume, volume, false));
            return result;
        }
    }

    public static int setStereoVolume(AudioTrack track, float left, float right) {
        synchronized (LOCK) {
            if (!valid(left) || !valid(right)) return track.setStereoVolume(left, right);
            float factor = factor();
            int result = track.setStereoVolume(left * factor, right * factor);
            if (result >= 0) PLAYERS.put(track, new Gains(left, right, true));
            return result;
        }
    }

    public static void setVolume(MediaPlayer player, float left, float right) {
        synchronized (LOCK) {
            if (!valid(left) || !valid(right)) {
                player.setVolume(left, right);
                return;
            }
            float factor = factor();
            player.setVolume(left * factor, right * factor);
            PLAYERS.put(player, new Gains(left, right, true));
        }
    }

    private static boolean valid(float volume) {
        // Comparisons also reject NaN and both infinities without newer framework APIs.
        return volume >= 0f && volume <= 1f;
    }

    private static float factor() {
        return ducking ? originalFraction : 1f;
    }

    /** Caller holds LOCK. Values contain no reference to their weak player keys. */
    private static void applyAll() {
        float factor = factor();
        Iterator<Map.Entry<Object, Gains>> iterator = PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, Gains> entry = iterator.next();
            Object player = entry.getKey();
            Gains gains = entry.getValue();
            if (player == null) {
                iterator.remove();
                continue;
            }
            try {
                if (player instanceof AudioTrack) {
                    AudioTrack track = (AudioTrack) player;
                    int result = gains.stereo
                            ? track.setStereoVolume(gains.left * factor, gains.right * factor)
                            : track.setVolume(gains.left * factor);
                    if (result < 0) iterator.remove();
                } else {
                    ((MediaPlayer) player).setVolume(gains.left * factor, gains.right * factor);
                }
            } catch (IllegalStateException released) {
                iterator.remove();
            }
        }
    }

    private static final class Gains {
        final float left;
        final float right;
        final boolean stereo;

        Gains(float left, float right, boolean stereo) {
            this.left = left;
            this.right = right;
            this.stereo = stereo;
        }
    }
}
