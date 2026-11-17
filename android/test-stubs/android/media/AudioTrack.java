package android.media;

/** Host-only fake: never package in the Android extension. */
public final class AudioTrack {
    public float left = 1f;
    public float right = 1f;
    public int result = 17;
    public int calls;
    public boolean released;
    public float lastArgument;

    public int setVolume(float gain) {
        return setStereoVolume(gain, gain);
    }

    public int setStereoVolume(float leftGain, float rightGain) {
        calls++;
        lastArgument = leftGain;
        if (released) throw new IllegalStateException("released");
        if (!Float.isFinite(leftGain) || !Float.isFinite(rightGain)
                || leftGain < 0 || rightGain < 0 || leftGain > 1 || rightGain > 1) {
            throw new IllegalArgumentException("native invalid volume");
        }
        if (result >= 0) { left = leftGain; right = rightGain; }
        return result;
    }
}
