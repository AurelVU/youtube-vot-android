package io.github.aurelvu.vot;
public final class VotPlaybackState {
 private VotPlaybackState(){}
 public static String label(boolean enabled,boolean prepared,boolean buffering,boolean audioPlaying,boolean videoPlaying){
  if(!enabled)return "Перевести видео";
  if(!prepared)return "Подготовка озвучки…";
  if(!videoPlaying)return "Перевод на паузе";
  if(buffering)return "Буферизация озвучки…";
  return audioPlaying?"Перевод включён":"Подготовка озвучки…";
 }
}
