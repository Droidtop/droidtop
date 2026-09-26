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

    /**
     * Named by string: :shell-default cannot depend on :app, which
     * declares all of these. droidtop's own package is hidden from app
     * lists except its one drawer icon (docs/SPEC.md 2c, "One droidtop
     * icon") -- but a home-screen WIDGET is a different, explicit-
     * placement surface the same rule was never meant to cover, and the
     * widget picker's own validity check (WidgetsModel.
     * WidgetValidityCheckForPicker) runs every real (non-custom) widget
     * item through this same shouldShowApp -- confirmed live on
     * emulator-5560: ContinuePlayingWidgetProvider and
     * PluginStatusWidgetProvider both silently vanished from droidtop's
     * own stock widget picker (Murine's own #custom-widget-scheme
     * smartspace clock entry still showed, since that path skips this
     * check entirely) until each was added here.
     */
    private static final Set<String> HIDE_SELF_EXEMPT_CLASSES = Set.of(
            "dev.droidtop.app.LauncherGamesActivity",
            "dev.droidtop.app.ContinuePlayingWidgetProvider",
            "dev.droidtop.app.PluginStatusWidgetProvider");

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
        // package is hidden except for its one icon, which opens setup,
        // Gaming or Desktop, or the library's games when both are off
        // (docs/SPEC.md 2c, "One droidtop icon").
        if (SettingsHiddenAppsFragment.HIDE_SELF && app.getPackageName().equals(mContext.getPackageName())
                && !HIDE_SELF_EXEMPT_CLASSES.contains(app.getClassName())) return false;
        return !HiddenAppsRepository.isHidden(mContext, app) || (retainSearchable && HiddenAppsRepository.isSearchHiddenAppsEnabled(mContext));
    }
}
