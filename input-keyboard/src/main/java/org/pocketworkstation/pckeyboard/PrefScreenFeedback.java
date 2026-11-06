/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.app.backup.BackupManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;

public class PrefScreenFeedback extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // droidtop patch (Droidtop/tracker#903 H12a): Vibrate on keypress is shown as Vibration strength from here on,
        // even before the keyboard itself has run since the update.
        KeyHapticsPlayer.migrate(PreferenceManager.getDefaultSharedPreferences(this));
        addPreferencesFromResource(R.xml.prefs_feedback);
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        prefs.registerOnSharedPreferenceChangeListener(this);
        mDuration = findPreference(KeyHaptics.LEN_PREF);
        showDuration(prefs);
    }

    // droidtop patch (#903 H12a): Hacker's "Vibrate duration" row, shown in pro mode or while the strength is Custom
    // (KeyHaptics.showsDuration). Hidden the way KeyboardProMode hides a pro row: taken off the screen, its value kept.
    private Preference mDuration;

    private void showDuration(SharedPreferences prefs) {
        if (mDuration == null) return;
        PreferenceScreen screen = getPreferenceScreen();
        boolean show = KeyHaptics.showsDuration(
                VibrationStrength.of(prefs.getString(KeyHaptics.PREF, null)), KeyboardProMode.isPro(prefs));
        boolean shown = screen.findPreference(KeyHaptics.LEN_PREF) != null;
        // Put back, it keeps its order from the XML, so it returns under Vibration strength.
        if (show && !shown) screen.addPreference(mDuration);
        else if (!show && shown) screen.removePreference(mDuration);
    }

    @Override
    protected void onDestroy() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
        super.onDestroy();
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        (new BackupManager(this)).dataChanged();
        if (KeyHaptics.PREF.equals(key)) showDuration(prefs);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
    }
}
