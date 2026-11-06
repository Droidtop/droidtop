package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.util.AttributeSet;

public class VibratePreference extends SeekBarPreferenceString {
    public VibratePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    @Override
    public void onChange(float val) {
        // droidtop patch (Droidtop/tracker#903 H12a): plays the length as Custom strength does, every key one buzz of it.
        KeyHapticsPlayer sample = new KeyHapticsPlayer(getContext());
        sample.setStrength(VibrationStrength.CUSTOM, (int) val);
        sample.play('a');
    }
}
