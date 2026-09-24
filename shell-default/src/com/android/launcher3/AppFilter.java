package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;

import app.murinelauncher.settings.SettingsHiddenAppsFragment;
import app.murinelauncher.settings.hiddenapps.HiddenAppsRepository;

import com.android.launcher3.dagger.ApplicationContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    /** Named by string: :shell-default cannot depend on :app, which declares it. */
    private static final String LAUNCHER_GAMES_ACTIVITY = "dev.droidtop.app.LauncherGamesActivity";

    private final Context mContext;
    private final Set<ComponentName> mFilteredComponents;

    @Inject
    public AppFilter(@ApplicationContext Context context) {
        mContext = context;
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
    }

    public boolean shouldShowApp(ComponentName app) {
        return shouldShowApp(app, false);
    }

    /**
     * @param retainSearchable: when true, hidden apps pass if "search within hidden apps" is enabled
     */
    public boolean shouldShowApp(ComponentName app, boolean retainSearchable) {
        if (mFilteredComponents.contains(app)) return false;
        // droidtop patch (not upstream Murine/Launcher3): droidtop's own
        // package is hidden except for its Games screen, which is how
        // Launcher mode shows the library (docs/SPEC.md, "Launcher mode").
        if (SettingsHiddenAppsFragment.HIDE_SELF && app.getPackageName().equals(mContext.getPackageName())
                && !LAUNCHER_GAMES_ACTIVITY.equals(app.getClassName())) return false;
        return !HiddenAppsRepository.isHidden(mContext, app) || (retainSearchable && HiddenAppsRepository.isSearchHiddenAppsEnabled(mContext));
    }
}
