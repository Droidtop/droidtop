package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

/**
 * "Vibration strength" (droidtop patch, Droidtop/tracker#903 H12a): Off, Light, Medium, Strong or Custom, in place of
 * Hacker's Keyboard's "Vibrate on keypress" (KeyHapticsPlayer.migrate moves its value in). Custom plays Hacker's
 * "Vibrate duration", which stays its own setting. Picking a strength plays a letter's haptic at it.
 */
public class VibrationStrengthPreference extends AutoSummaryListPreference {
    public VibrationStrengthPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        KeyHapticsPlayer sample = new KeyHapticsPlayer(getContext());
        sample.setStrength(VibrationStrength.of(newValue == null ? null : newValue.toString()),
                KeyHapticsPlayer.storedLength(PreferenceManager.getDefaultSharedPreferences(getContext())));
        sample.play('a');
        return super.callChangeListener(newValue);
    }
}
