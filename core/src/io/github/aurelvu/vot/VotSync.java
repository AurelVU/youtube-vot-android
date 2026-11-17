package io.github.aurelvu.vot;

/** Monotonic video clock and translated-audio synchronization policy, in seconds. */
public final class VotSync {
    private boolean clockInitialized;
    private double lastTime;
    private double lastSample;
    private double lastProgress = Double.NEGATIVE_INFINITY;
    private double anchorTime;
    private double anchorHost;
    private double cadence = 0.25;
    private boolean discontinuity;
    private boolean pendingDiscontinuity;
    private boolean syncInitialized;
    private boolean wasMoving;
    private double lastSeekAt = Double.NEGATIVE_INFINITY;
    private double largeDriftSince = Double.NaN;

    /** Clear both the native clock and the audio correction history on video changes. */
    public void reset() {
        resetClock();
        syncInitialized = false;
        wasMoving = false;
        lastSeekAt = Double.NEGATIVE_INFINITY;
        largeDriftSince = Double.NaN;
    }

    private void resetClock() {
        clockInitialized = false;
        lastTime = lastSample = anchorTime = anchorHost = 0;
        lastProgress = Double.NEGATIVE_INFINITY;
        cadence = 0.25;
        discontinuity = pendingDiscontinuity = false;
    }

    /**
     * Record a native position snapshot. Duplicate snapshots retain motion for the
     * cadence-based grace period; an initial sample or a seek does not imply playback.
     */
    public boolean observe(double videoSeconds, double monotonicSeconds, float nativeRate) {
        if (!validPosition(videoSeconds) || !validPosition(monotonicSeconds) || !validRate(nativeRate)) {
            resetClock();
            return false;
        }
        if (!clockInitialized) {
            clockInitialized = true;
            lastTime = anchorTime = videoSeconds;
            lastSample = anchorHost = monotonicSeconds;
            return false;
        }
        discontinuity = false;
        if (monotonicSeconds < lastSample) {
            resetClock();
            return false;
        }
        double delta = videoSeconds - anchorTime;
        double interval = monotonicSeconds - anchorHost;
        if (Math.abs(delta) > 0.01) {
            discontinuity = delta < -0.25
                    || Math.abs(delta - interval * nativeRate) > Math.max(1.25, nativeRate * 0.75);
            pendingDiscontinuity |= discontinuity;
            if (delta > 0 && interval >= 0.03 && interval <= 2.5 && !discontinuity) {
                cadence = Double.isFinite(lastProgress) ? cadence * 0.8 + interval * 0.2 : interval;
            }
            anchorTime = videoSeconds;
            anchorHost = monotonicSeconds;
            lastProgress = discontinuity ? Double.NEGATIVE_INFINITY : monotonicSeconds;
        }
        lastTime = videoSeconds;
        lastSample = monotonicSeconds;
        return monotonicSeconds - lastProgress < motionGrace();
    }

    /** Project the latest moving native snapshot using host monotonic time. */
    public double projectedTime(double monotonicSeconds, float nativeRate) {
        if (!clockInitialized || !validPosition(monotonicSeconds) || !validRate(nativeRate)
                || monotonicSeconds < lastSample) return Double.NaN;
        if (monotonicSeconds - lastProgress >= motionGrace()) return lastTime;
        return anchorTime + Math.max(0, monotonicSeconds - anchorHost) * nativeRate;
    }

    /** Whether the most recent observation detected a native seek. */
    public boolean discontinuity() {
        return discontinuity;
    }

    private double motionGrace() {
        return Math.min(2.5, Math.max(0.75, cadence * 2.5));
    }

    /**
     * Smooth iOS synchronization with a persistent-drift timer and seek cooldown.
     * Native play/pause should also gate {@code moving}; motion alone is only a clock hint.
     * A detected native seek forces one alignment, even while native playback is paused.
     */
    public Decision decide(double projectedVideoSeconds, double audioSeconds, float rate,
                           boolean moving, boolean allowed, double monotonicSeconds) {
        if (!validPosition(projectedVideoSeconds) || !validPosition(audioSeconds) || !validRate(rate)
                || !validPosition(monotonicSeconds) || !allowed) return stopped(projectedVideoSeconds, rate);
        Decision basic = basicDecision(projectedVideoSeconds, audioSeconds, rate, moving, true);
        double drift = Math.abs(projectedVideoSeconds - audioSeconds) / rate;
        boolean seek;
        if (!syncInitialized) {
            syncInitialized = true;
            seek = pendingDiscontinuity || drift > 0.2;
        } else if (pendingDiscontinuity) {
            seek = true;
        } else if (!wasMoving && moving && drift > 0.35 && monotonicSeconds - lastSeekAt > 0.25) {
            seek = true;
        } else {
            seek = false;
            boolean saturated = rate >= 3.99f && projectedVideoSeconds > audioSeconds && drift > 0.2;
            if (moving && (drift > 2.5 || saturated)) {
                if (!Double.isFinite(largeDriftSince)) largeDriftSince = monotonicSeconds;
                if (monotonicSeconds - largeDriftSince >= 0.8 && monotonicSeconds - lastSeekAt >= 3) seek = true;
            } else {
                largeDriftSince = Double.NaN;
            }
        }
        pendingDiscontinuity = false;
        if (seek) {
            lastSeekAt = monotonicSeconds;
            largeDriftSince = Double.NaN;
        }
        wasMoving = moving;
        return new Decision(moving, seek, projectedVideoSeconds, seek ? rate : basic.rate);
    }

    public static final class Decision {
        public final boolean play;
        public final boolean seek;
        public final double target;
        public final float rate;

        private Decision(boolean play, boolean seek, double target, float rate) {
            this.play = play;
            this.seek = seek;
            this.target = target;
            this.rate = rate;
        }
    }

    /** Stateless iOS drift policy, with invalid audio positions rejected at the boundary. */
    public static Decision basicDecision(double videoSeconds, double audioSeconds, float rate,
                                         boolean moving, boolean allowed) {
        if (!validPosition(videoSeconds) || !validPosition(audioSeconds) || !validRate(rate) || !allowed)
            return stopped(videoSeconds, rate);
        double error = (videoSeconds - audioSeconds) / rate;
        boolean seek = Math.abs(error) > 2.5;
        float adjustedRate = rate;
        if (moving && !seek && Math.abs(error) > 0.2) {
            double correction = Math.max(-0.03, Math.min(0.03, error * 0.05));
            adjustedRate = (float) Math.min(4, Math.max(0.1, rate * (1 + correction)));
        }
        return new Decision(moving, seek, videoSeconds, adjustedRate);
    }

    private static boolean validPosition(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static boolean validRate(float rate) {
        return Float.isFinite(rate) && rate > 0 && rate <= 4;
    }

    private static Decision stopped(double target, float rate) {
        return new Decision(false, false, validPosition(target) ? target : 0, validRate(rate) ? rate : 1);
    }
}
