package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;

import app.murinelauncher.settings.SettingsHiddenAppsFragment;
import app.murinelauncher.settings.hiddenapps.HiddenAppsRepository;

import com.android.launcher3.dagger.ApplicationContext;

import dev.droidtop.runtime.LauncherSelfFilter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    /**
     * droidtop patch: which of droidtop's own components show while its package is hidden is
     * LauncherSelfFilter's one rule (the one icon and every widget provider). Widgets go through this same
     * shouldShowApp in the picker (WidgetsModel.WidgetValidityCheckForPicker), and a hand list of classes
     * here once dropped seven of droidtop's nine widgets from it (rig, 2026-10-16). Whether a component is a
     * widget provider is read from its receiver's metadata once and remembered.
     */
    private final java.util.Map<ComponentName, Boolean> mSelfWidgetProviders =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Whether droidtop's own [app] is an app widget provider (enabled or not: Desktop's is gated by mode). */
    private boolean isSelfWidgetProvider(ComponentName app) {
        return mSelfWidgetProviders.computeIfAbsent(app, component -> {
            try {
                android.content.pm.ActivityInfo receiver = mContext.getPackageManager().getReceiverInfo(component,
                        android.content.pm.PackageManager.GET_META_DATA
                                | android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                return receiver.metaData != null
                        && receiver.metaData.containsKey(android.appwidget.AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // An activity, or a component that is gone.
                return false;
            }
        });
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
                && !LauncherSelfFilter.INSTANCE.shown(app.getClassName(), isSelfWidgetProvider(app))) return false;
        return !HiddenAppsRepository.isHidden(mContext, app) || (retainSearchable && HiddenAppsRepository.isSearchHiddenAppsEnabled(mContext));
    }
}
