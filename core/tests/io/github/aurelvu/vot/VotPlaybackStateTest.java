package io.github.aurelvu.vot;
public final class VotPlaybackStateTest {
 public static void main(String[] args) {
  check(VotPlaybackState.label(true,false,false,false,true).equals("Подготовка озвучки…"));
  check(VotPlaybackState.label(true,true,true,false,true).equals("Буферизация озвучки…"));
  check(VotPlaybackState.label(true,true,false,true,false).equals("Перевод на паузе"));
  check(VotPlaybackState.label(true,true,false,false,true).equals("Подготовка озвучки…"));
  check(VotPlaybackState.label(true,true,false,true,true).equals("Перевод включён"));
  check(VotPlaybackState.label(false,true,false,true,true).equals("Перевести видео"));
  System.out.println("Playback state: 6 assertions passed");
 }
 private static void check(boolean b){if(!b)throw new AssertionError("Incorrect playback readiness label");}
}
