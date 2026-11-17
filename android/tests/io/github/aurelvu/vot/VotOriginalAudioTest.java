package io.github.aurelvu.vot;

import android.media.AudioTrack;
import android.media.MediaPlayer;

public final class VotOriginalAudioTest {
    private static int assertions;
    private static int failures;

    public static void main(String[] args) {
        run("normal native gains and result", VotOriginalAudioTest::normal);
        run("new native players inherit ducking", VotOriginalAudioTest::newPlayers);
        run("active fraction changes", VotOriginalAudioTest::fractionChanges);
        run("native gain changes do not compound", VotOriginalAudioTest::nativeChanges);
        run("disable restores stereo gains", VotOriginalAudioTest::disable);
        run("restoreAll disables and restores latest gains", VotOriginalAudioTest::restore);
        run("unwrapped translated player remains independent", VotOriginalAudioTest::translated);
        run("released players do not break restore", VotOriginalAudioTest::released);
        run("invalid arguments reach native API", VotOriginalAudioTest::invalid);
        run("failed native write is not cached", VotOriginalAudioTest::failedWrite);
        System.out.println("VotOriginalAudioTest: " + assertions + " assertions; " + failures + " failed groups");
        if (failures != 0) throw new AssertionError("Original audio groups failed: " + failures);
    }

    private static void normal() {
        AudioTrack track = new AudioTrack();
        equal(17, VotOriginalAudio.setVolume(track, .7f));
        gain(.7f, .7f, track);
        equal(17, VotOriginalAudio.setStereoVolume(track, .8f, .4f));
        gain(.8f, .4f, track);
        MediaPlayer player = new MediaPlayer();
        VotOriginalAudio.setVolume(player, .3f, .9f);
        gain(.3f, .9f, player);
    }

    private static void newPlayers() {
        VotOriginalAudio.setOriginalFraction(.25f);
        VotOriginalAudio.setDucking(true);
        AudioTrack track = new AudioTrack();
        VotOriginalAudio.setVolume(track, .8f);
        gain(.2f, .2f, track);
        MediaPlayer player = new MediaPlayer();
        VotOriginalAudio.setVolume(player, .4f, .8f);
        gain(.1f, .2f, player);
    }

    private static void fractionChanges() {
        AudioTrack track = new AudioTrack();
        MediaPlayer player = new MediaPlayer();
        VotOriginalAudio.setStereoVolume(track, .6f, .2f);
        VotOriginalAudio.setVolume(player, .4f, .8f);
        VotOriginalAudio.setOriginalFraction(.5f);
        gain(.6f, .2f, track);
        VotOriginalAudio.setDucking(true);
        gain(.3f, .1f, track); gain(.2f, .4f, player);
        VotOriginalAudio.setOriginalFraction(.25f);
        gain(.15f, .05f, track); gain(.1f, .2f, player);
        VotOriginalAudio.setOriginalFraction(0);
        gain(0, 0, track); gain(0, 0, player);
        VotOriginalAudio.setOriginalFraction(1);
        gain(.6f, .2f, track); gain(.4f, .8f, player);
    }

    private static void nativeChanges() {
        AudioTrack track = new AudioTrack();
        VotOriginalAudio.setOriginalFraction(.5f);
        VotOriginalAudio.setDucking(true);
        for (int i = 0; i < 5; i++) VotOriginalAudio.setVolume(track, .8f);
        gain(.4f, .4f, track);
        VotOriginalAudio.setStereoVolume(track, .6f, .2f);
        gain(.3f, .1f, track);
        VotOriginalAudio.restoreAll();
        gain(.6f, .2f, track);
        MediaPlayer player = new MediaPlayer();
        VotOriginalAudio.setDucking(true);
        VotOriginalAudio.setVolume(player, .2f, .8f);
        VotOriginalAudio.setVolume(player, .6f, .4f);
        gain(.3f, .2f, player);
        VotOriginalAudio.restoreAll();
        gain(.6f, .4f, player);
    }

    private static void disable() {
        AudioTrack track = new AudioTrack();
        VotOriginalAudio.setStereoVolume(track, .2f, .8f);
        VotOriginalAudio.setOriginalFraction(.25f);
        VotOriginalAudio.setDucking(true);
        VotOriginalAudio.setDucking(false);
        gain(.2f, .8f, track);
        VotOriginalAudio.setDucking(true);
        gain(.05f, .2f, track);
    }

    private static void restore() {
        AudioTrack track = new AudioTrack();
        VotOriginalAudio.setOriginalFraction(.5f);
        VotOriginalAudio.setDucking(true);
        VotOriginalAudio.setVolume(track, .3f);
        VotOriginalAudio.restoreAll();
        gain(.3f, .3f, track);
        VotOriginalAudio.setVolume(track, .9f);
        gain(.9f, .9f, track);
        VotOriginalAudio.restoreAll();
        gain(.9f, .9f, track);
    }

    private static void translated() {
        MediaPlayer translation = new MediaPlayer();
        translation.setVolume(.8f, .7f);
        VotOriginalAudio.setOriginalFraction(0);
        VotOriginalAudio.setDucking(true);
        gain(.8f, .7f, translation);
        equal(1, translation.calls);
        VotOriginalAudio.restoreAll();
        equal(1, translation.calls);
    }

    private static void released() {
        AudioTrack track = new AudioTrack();
        MediaPlayer player = new MediaPlayer();
        AudioTrack healthy = new AudioTrack();
        VotOriginalAudio.setVolume(track, .8f);
        VotOriginalAudio.setVolume(player, .8f, .8f);
        VotOriginalAudio.setVolume(healthy, .8f);
        VotOriginalAudio.setOriginalFraction(.5f);
        VotOriginalAudio.setDucking(true);
        track.released = true; player.released = true;
        VotOriginalAudio.restoreAll();
        gain(.8f, .8f, healthy);
        int calls = track.calls + player.calls;
        VotOriginalAudio.setDucking(true);
        VotOriginalAudio.setOriginalFraction(.25f);
        equal(calls, track.calls + player.calls);
    }

    private static void invalid() {
        AudioTrack track = new AudioTrack();
        MediaPlayer player = new MediaPlayer();
        VotOriginalAudio.setVolume(track, .8f);
        VotOriginalAudio.setVolume(player, .8f, .4f);
        VotOriginalAudio.setOriginalFraction(.5f);
        VotOriginalAudio.setDucking(true);
        rejects(() -> VotOriginalAudio.setVolume(track, -1f));
        close(-1f, track.lastArgument);
        rejects(() -> VotOriginalAudio.setStereoVolume(track, 2f, .5f));
        close(2f, track.lastArgument);
        rejects(() -> VotOriginalAudio.setVolume(player, Float.NaN, .5f));
        VotOriginalAudio.restoreAll();
        gain(.8f, .8f, track); gain(.8f, .4f, player);
        rejects(() -> VotOriginalAudio.setOriginalFraction(Float.NaN));
        rejects(() -> VotOriginalAudio.setOriginalFraction(-.1f));
        rejects(() -> VotOriginalAudio.setOriginalFraction(1.1f));
        rejects(() -> VotOriginalAudio.setOriginalFraction(Float.POSITIVE_INFINITY));
        AudioTrack untouched = new AudioTrack();
        rejects(() -> VotOriginalAudio.setVolume(untouched, 2));
        int calls = untouched.calls;
        VotOriginalAudio.setDucking(true);
        equal(calls, untouched.calls);
    }

    private static void failedWrite() {
        AudioTrack track = new AudioTrack();
        track.result = -3;
        equal(-3, VotOriginalAudio.setVolume(track, .4f));
        int calls = track.calls;
        VotOriginalAudio.setDucking(true);
        equal(calls, track.calls);
        track.result = 17;
        VotOriginalAudio.setVolume(track, .8f);
        track.result = -3;
        equal(-3, VotOriginalAudio.setVolume(track, .2f));
        track.result = 17;
        VotOriginalAudio.restoreAll();
        gain(.8f, .8f, track);
        track.result = -3;
        VotOriginalAudio.setDucking(true);
        int failedCalls = track.calls;
        VotOriginalAudio.setOriginalFraction(.2f);
        equal(failedCalls, track.calls);
    }

    private static void run(String name, Runnable action) {
        try {
            VotOriginalAudio.restoreAll();
            VotOriginalAudio.setOriginalFraction(1);
            action.run();
        } catch (Throwable e) {
            failures++;
            System.err.println("FAIL " + name + ": " + e);
        }
    }

    private static void gain(float left, float right, AudioTrack track) { close(left, track.left); close(right, track.right); }
    private static void gain(float left, float right, MediaPlayer player) { close(left, player.left); close(right, player.right); }
    private static void close(float expected, float actual) {
        assertions++;
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > .000001f) throw new AssertionError("Expected gain " + expected + ", got " + actual);
    }
    private static void equal(int expected, int actual) {
        assertions++;
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected native IllegalArgumentException");
    }
}
