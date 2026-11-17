package io.github.aurelvu.vot;

/** Host fixtures: javac --release 17, then java -ea io.github.aurelvu.vot.VotSyncTest. */
public final class VotSyncTest {
    private static int assertions;

    public static void main(String[] args) {
        basicPolicy();
        sparseClock();
        smoothPolicy();
        discontinuitiesAndReset();
        invalidInputs();
        for (double cadence : new double[] {0.25, 0.5, 1})
            for (float speed : new float[] {1, 2, 4}) continuousPlayback(cadence, speed);
        System.out.println("VotSyncTest passed (" + assertions + " assertions)");
    }

    private static void basicPolicy() {
        decision(VotSync.basicDecision(20, 20.1, 1.5f, true, true), true, false, 20, 1.5f);
        decision(VotSync.basicDecision(80, 20, 1, true, true), true, true, 80, 1);
        decision(VotSync.basicDecision(20, 20, 1, false, true), false, false, 20, 1);
        check(!VotSync.basicDecision(20, 20, 1, true, false).play, "disabled audio stays paused");
        decision(VotSync.basicDecision(20, 20.2, 2, true, true), true, false, 20, 2);
        decision(VotSync.basicDecision(20.45, 20, 3, true, true), true, false, 20.45, 3);
        decision(VotSync.basicDecision(20.6, 20, 4, true, true), true, false, 20.6, 4);
        VotSync.Decision behind = VotSync.basicDecision(10.6, 10, 1, true, true);
        check(behind.play && !behind.seek && behind.rate > 1 && behind.rate <= 1.031f,
                "audio behind video catches up by at most three percent");
        VotSync.Decision ahead = VotSync.basicDecision(10, 10.6, 1, true, true);
        check(!ahead.seek && ahead.rate < 1 && ahead.rate >= 0.969f,
                "audio ahead slows by at most three percent");
        check(!VotSync.basicDecision(12.5, 10, 1, true, true).seek, "2.5s threshold is exclusive");
        check(VotSync.basicDecision(12.501, 10, 1, true, true).seek, "larger drift seeks");
    }


    private static void sparseClock() {
        VotSync clock = new VotSync();
        check(!clock.observe(0, 0, 1), "first observation cannot establish motion");
        check(clock.observe(1, 1, 1), "normal one-second sample establishes motion");
        check(clock.observe(1, 1.8, 1), "sparse tick retains motion within cadence grace");
        close(clock.projectedTime(1.8, 1), 1.8, "project between sparse snapshots");
        check(!clock.observe(1, 4, 1), "stalled source eventually stops motion");
        close(clock.projectedTime(4, 1), 1, "stalled clock freezes at last native position");
        clock.reset();
        check(Double.isNaN(clock.projectedTime(4, 1)), "reset drops old clock anchor");
        check(!clock.observe(0, 10, 1), "reset requires a new motion observation");
        check(clock.observe(0.15, 10.15, 1), "quarter-second clock starts");
        check(clock.observe(0.15, 10.30, 1), "repeated sample is not a pause");
        check(clock.observe(0.45, 10.45, 1), "progress refreshes grace");
        check(clock.observe(0.45, 10.6, 1), "normal duplicate keeps playing");
        check(!clock.observe(0.45, 11.2, 1), "short-cadence clock detects pause");
        check(clock.observe(0.6, 11.35, 1), "new progress resumes motion");
        check(!clock.observe(0.6, 10, 1), "monotonic regression invalidates motion");
        check(Double.isNaN(clock.projectedTime(10, 1)), "regressed clock must reanchor");
    }

    private static void smoothPolicy() {
        VotSync sync = new VotSync();
        check(!sync.decide(10, 10, 1, true, true, 0).seek, "aligned start does not seek");
        VotSync.Decision d = sync.decide(10.6, 10, 1, true, true, 0.15);
        check(d.play && !d.seek && d.rate > 1 && d.rate <= 1.031f, "small lag smoothly accelerates");
        d = sync.decide(10, 10.6, 1, true, true, 0.3);
        check(!d.seek && d.rate < 1 && d.rate >= 0.969f, "small lead smoothly decelerates");
        check(!sync.decide(15, 10, 1, true, true, 0.45).seek, "transient large drift does not seek");
        check(!sync.decide(10, 10, 1, true, true, 0.6).seek, "recovery clears large-drift timer");
        check(!sync.decide(15, 10, 1, true, true, 1).seek, "persistent drift timer begins anew");
        check(!sync.decide(15.7, 10.7, 1, true, true, 1.7).seek, "drift under 0.8 seconds waits");
        decision(sync.decide(15.9, 10.9, 1, true, true, 1.9), true, true, 15.9, 1);
        check(!sync.decide(16, 11, 1, true, true, 2).seek, "seek cooldown prevents immediate retry");
        check(!sync.decide(17, 12, 1, true, true, 3).seek, "large drift cannot bypass three-second cooldown");
        check(sync.decide(19, 14, 1, true, true, 5).seek, "persistent drift seeks after cooldown");
        sync.reset();
        check(sync.decide(10.3, 10, 1, true, true, 0).seek, "initial alignment uses 0.2-second threshold");
        check(!sync.decide(10.3, 10, 1, false, true, 0.1).play, "explicit native pause pauses audio");
        check(!sync.decide(11, 10, 1, true, true, 0.2).seek, "resume inside short cooldown waits");
        sync.decide(11, 10, 1, false, true, 0.3);
        check(sync.decide(11, 10, 1, true, true, 0.4).seek, "resume aligns drift beyond 0.35 seconds");
        sync.reset();
        sync.decide(10, 10, 4, true, true, 0);
        check(!sync.decide(12, 10, 4, true, true, 0.15).seek, "4x saturation waits for sustained lag");
        check(sync.decide(16, 14, 4, true, true, 1.2).seek, "4x saturation eventually seeks because speed cannot catch up");
        sync.reset();
        sync.decide(10, 10, 4, true, true, 0);
        check(!sync.decide(10, 12, 4, true, true, 0.15).seek, "4x audio ahead uses rate correction");
        check(!sync.decide(14, 16, 4, true, true, 1.2).seek, "4x saturation only applies to audio behind");
    }

    private static void discontinuitiesAndReset() {
        VotSync sync = new VotSync();
        sync.observe(5, 0, 1);
        sync.decide(5, 5, 1, false, true, 0);
        check(!sync.observe(40, 0.15, 1) && sync.discontinuity(), "forward seek is not normal playback");
        decision(sync.decide(40, 5, 1, false, true, 0.15), false, true, 40, 1);
        check(!sync.decide(40, 5, 1, false, true, 0.2).seek, "one native discontinuity forces only one seek");
        check(!sync.observe(40, 0.3, 1) && !sync.discontinuity(), "duplicate post-seek sample stays stopped");
        check(sync.observe(40.15, 0.45, 1), "post-seek progress restores motion");
        check(!sync.observe(4, 0.6, 1) && sync.discontinuity(), "backward seek is a discontinuity");
        check(sync.decide(4, 40, 1, false, true, 0.6).seek, "backward seek bypasses normal cooldown");
        sync.reset();
        check(!sync.discontinuity(), "reset clears pending discontinuity");
        check(sync.decide(20, 10, 1, true, true, 0).seek, "reset allows initial audio alignment");
    }

    private static void invalidInputs() {
        double[] bad = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1};
        for (double value : bad) {
            VotSync sync = new VotSync();
            inactive(VotSync.basicDecision(value, 10, 1, true, true), "invalid basic video");
            inactive(VotSync.basicDecision(10, value, 1, true, true), "invalid basic audio");
            inactive(sync.decide(value, 10, 1, true, true, 0), "invalid smooth video");
            inactive(sync.decide(10, value, 1, true, true, 0), "invalid smooth audio");
            inactive(sync.decide(10, 10, 1, true, true, value), "invalid host time");
            check(!sync.observe(value, 0, 1), "invalid observation does not move");
            check(!sync.observe(10, value, 1), "invalid observation host time does not move");
            check(Double.isNaN(sync.projectedTime(value, 1)), "invalid projection host time yields no clock");
        }
        for (float rate : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1, 0, 4.01f}) {
            VotSync sync = new VotSync();
            inactive(VotSync.basicDecision(10, 10, rate, true, true), "invalid basic rate");
            inactive(sync.decide(10, 10, rate, true, true, 0), "invalid smooth rate");
            check(!sync.observe(10, 0, rate), "invalid native rate does not move");
            check(Double.isNaN(sync.projectedTime(0, rate)), "invalid rate yields no projection");
        }
        VotSync sync = new VotSync();
        inactive(sync.decide(30, 10, 1, true, false, 0), "disallowed audio cannot play or seek");
        check(sync.decide(30, 10, 1, true, true, 0.1).seek, "disabled decision does not consume initial alignment");
    }

    /** Simulate sparse source snapshots and a 120ms asynchronous audio seek for two minutes. */
    private static void continuousPlayback(double cadence, float speed) {
        VotSync sync = new VotSync();
        double audio = 0, actualRate = 0, finishAt = 0, seekTarget = 0, largestDrift = 0;
        boolean pending = false, resumeAfterSeek = false;
        int seeks = 0, interruptions = 0;
        for (int tick = 0; tick < 800; tick++) {
            double now = tick * 0.15;
            if (pending && now >= finishAt) {
                audio = seekTarget + (resumeAfterSeek ? (now - finishAt) * speed : 0);
                actualRate = resumeAfterSeek ? speed : 0;
                pending = false;
            } else if (!pending) {
                audio += tick > 0 ? actualRate * 0.15 : 0;
            }
            double snapshot = 10 + Math.floor((now + 1e-8) / cadence) * cadence * speed;
            boolean moving = sync.observe(snapshot, now, speed);
            double master = sync.projectedTime(now, speed);
            VotSync.Decision d = sync.decide(master, audio, speed, moving, true, now);
            if (now > 3 && !d.play) interruptions++;
            if (!pending && d.seek) {
                seeks++;
                pending = true;
                finishAt = now + 0.12;
                seekTarget = d.target;
                resumeAfterSeek = d.play;
                actualRate = 0;
            } else if (!pending) {
                actualRate = d.play ? d.rate : 0;
            }
            if (now > 4) largestDrift = Math.max(largestDrift, Math.abs(master - audio) / speed);
        }
        String label = "cadence=" + cadence + "s speed=" + speed + "x";
        System.out.printf("%s seeks=%d interruptions=%d maxDrift=%.3fs%n", label, seeks, interruptions, largestDrift);
        check(seeks <= 2, label + " avoids repeated hard seeks");
        check(interruptions == 0, label + " avoids false pauses");
        check(largestDrift < 0.5, label + " drift stays under half a second");
    }

    private static void inactive(VotSync.Decision d, String message) {
        check(!d.play && !d.seek, message);
        check(Double.isFinite(d.target) && d.target >= 0 && Float.isFinite(d.rate) && d.rate > 0,
                message + " has safe decision fields");
    }

    private static void decision(VotSync.Decision d, boolean play, boolean seek, double target, float rate) {
        check(d.play == play, "play expected " + play);
        check(d.seek == seek, "seek expected " + seek);
        close(d.target, target, "seek target");
        close(d.rate, rate, "playback rate");
    }

    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
