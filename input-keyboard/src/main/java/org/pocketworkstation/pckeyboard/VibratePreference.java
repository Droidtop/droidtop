package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.util.AttributeSet;

/**
 * "Vibration strength" (droidtop patch, Droidtop/tracker#903 H12a): Off, Light, Medium or Strong, in place of Hacker's
 * Keyboard's "Vibrate on keypress" and "Vibrate duration" (KeyHapticsPlayer.migrate moves their value in). Picking a
 * strength plays a letter's haptic at it, as the duration slider used to play its length.
 */
public class VibratePreference extends AutoSummaryListPreference {
    public VibratePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        KeyHapticsPlayer sample = new KeyHapticsPlayer(getContext());
        sample.setStrength(VibrationStrength.of(newValue == null ? null : newValue.toString()));
        sample.play('a');
        return super.callChangeListener(newValue);
    }
}
