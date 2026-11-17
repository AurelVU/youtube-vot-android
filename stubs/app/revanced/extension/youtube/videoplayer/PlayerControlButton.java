package app.revanced.extension.youtube.videoplayer;

import android.view.View;

/** Compile-only API declaration. Never include this class in the extension DEX. */
public class PlayerControlButton {
    public interface PlayerControlButtonStatus { boolean buttonEnabled(); }
    public PlayerControlButton(View controls, String id, String textOverlayId,
                               PlayerControlButtonStatus status, View.OnClickListener click,
                               View.OnLongClickListener longClick) { throw new UnsupportedOperationException("Compile-only stub"); }
    public void setVisibility(boolean visible, boolean animated) { throw new UnsupportedOperationException("Compile-only stub"); }
    public void setVisibilityImmediate(boolean visible) { throw new UnsupportedOperationException("Compile-only stub"); }
    public void setVisibilityNegatedImmediate() { throw new UnsupportedOperationException("Compile-only stub"); }
}
