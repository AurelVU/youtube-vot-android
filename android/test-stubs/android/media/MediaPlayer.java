package android.media;

/** Host-only fake: never package in the Android extension. */
public final class MediaPlayer {
    public float left = 1f;
    public float right = 1f;
    public int calls;
    public boolean released;

    public void setVolume(float leftGain, float rightGain) {
        calls++;
        if (released) throw new IllegalStateException("released");
        if (!Float.isFinite(leftGain) || !Float.isFinite(rightGain)
                || leftGain < 0 || rightGain < 0 || leftGain > 1 || rightGain > 1) {
            throw new IllegalArgumentException("native invalid volume");
        }
        left = leftGain; right = rightGain;
    }
}
