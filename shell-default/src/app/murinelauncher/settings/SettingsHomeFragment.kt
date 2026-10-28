package app.murinelauncher.settings

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.receiver.ScreenOffAdminReceiver
import app.murinelauncher.widget.CustomSeekBarPreference
import app.murinelauncher.widget.radio.RadioGroupPreference
import app.murinelauncher.widget.smartspace.SmartspaceMode
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.touch.GestureAction
import com.android.launcher3.ConstantItem
import dev.droidtop.runtime.HomeAction
import dev.droidtop.shell.standard.HomeActions
import com.android.launcher3.Flags
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.SessionCommitReceiver
import com.android.launcher3.Utilities
import com.android.launcher3.states.RotationHelper
import com.android.launcher3.util.DisplayController

public final class SettingsHomeFragment: AbstractSettingsFragment() {

    companion object {
        const val FIXED_LANDSCAPE_MODE: String = "pref_fixed_landscape_mode"
        const val GRID_SIZE_WIDTH: String = "pref_grid_size_width"
        const val GRID_SIZE_HEIGHT: String = "pref_grid_size_height"
        const val LOCK_HOME_SCREEN: String = "pref_lock_home_screen"
        const val WRAP_PAGES: String = "pref_wrap_pages"
        const val DOCK_ICONS_OVERRIDE_SWITCH: String = "pref_dock_icons_override_switch"
        const val DOCK_ICONS: String = "pref_dock_icons"
        const val DOUBLE_TAP_ACTION: String = "pref_gesture_double_tap_action"
        const val SWIPE_DOWN_ACTION: String = "pref_gesture_swipe_down_action"
        const val TWO_FINGER_DOWN_ACTION: String = "pref_gesture_two_finger_down_action"
        const val TWO_FINGER_UP_ACTION: String = "pref_gesture_two_finger_up_action"
        const val PINCH_IN_ACTION: String = "pref_gesture_pinch_in_action"
        const val PINCH_OUT_ACTION: String = "pref_gesture_pinch_out_action"
        const val HOME_BUTTON_ACTION: String = "pref_gesture_home_button_action"
        const val SMARTSPACE_MODE: String = "pref_smartspace_mode"
        const val TASKBAR: String = "pref_taskbar"
        private const val REQUEST_DEVICE_ADMIN = 1001

        @JvmStatic @RequiresApi(Build.VERSION_CODES.P)
        fun requestAccessibilityPermission(context: Context): Boolean {
            // droidtop's one accessibility service (Droidtop/tracker#740), turned on the one way: elevated access
            // when droidtop has it, else Android's accessibility page.
            if (!dev.droidtop.runtime.keyboard.AccessibilityKeyboard.isActive(context)) {
                Thread {
                    dev.droidtop.runtime.keyboard.AccessibilityKeyboard.turnOn(context)
                }.start()
                dev.droidtop.library.settings.Notices.flash(context.resources.getString(R.string.pref_accessibility_request_toast), context = context)
                return false
            }
            return true
        }
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_home

    override fun getPreferenceTitle(): Int? = R.string.pref_category_home_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        val isGoogleSmartspaceAvailable = kotlin.lazy { SmartspaceMode.isGoogleSmartspaceAvailable(requireContext()) }
        var isTablet = InvariantDeviceProfile.INSTANCE.get(requireContext()).deviceType == InvariantDeviceProfile.TYPE_TABLET;
        // The gesture slots' rows, from the one table of slots (GestureSlot).
        com.android.launcher3.touch.GestureSlot.bySettingKey(preference.key)?.let { slot ->
            bindGestureAction(preference as RadioGroupPreference, slot.pref)
            return true
        }
        when (preference.key) {
            RotationHelper.ALLOW_ROTATION_PREFERENCE_KEY -> {
                if (Flags.oneGridSpecs()) {
                    return false
                }
                if (info.isTablet(info.realBounds)) {
                    // Launcher supports rotation by default. No need to show this setting.
                    return false
                }
                // Initialize the UI once
                preference.setDefaultValue(RotationHelper.getAllowRotationDefaultValue(info))
                return true
            }
            FIXED_LANDSCAPE_MODE -> {
                if (!Flags.oneGridSpecs() // adding this condition until fixing b/378972567
                    || (InvariantDeviceProfile.INSTANCE.get(getContext()).deviceType
                            == InvariantDeviceProfile.TYPE_MULTI_DISPLAY) || (InvariantDeviceProfile.INSTANCE.get(
                        getContext()
                    ).deviceType
                            == InvariantDeviceProfile.TYPE_TABLET)
                ) {
                    return false
                }
                // When the setting changes rotate the screen accordingly to showcase the result
                // of the setting
                preference.setOnPreferenceChangeListener(
                    Preference.OnPreferenceChangeListener { pref: Preference?, newValue: Any? ->
                        requireActivity().setRequestedOrientation(
                            if (newValue as Boolean)
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else
                                ActivityInfo.SCREEN_ORIENTATION_USER
                        )
                        true
                    }
                )
                return !info.isTablet(info.realBounds)
            }
            GRID_SIZE_WIDTH -> {
                preference.setDefaultValue(LauncherPrefs.defaultGridWidth(isTablet))
                return true
            }
            GRID_SIZE_HEIGHT -> {
                preference.setDefaultValue(LauncherPrefs.defaultGridHeight(isTablet))
                return true
            }
            // The dock's own size, as the drawer's own width is set (SettingsDrawerFragment): off is 0, the grid's
            // width; on starts at the grid's width.
            DOCK_ICONS_OVERRIDE_SWITCH -> {
                val launcherPrefs = LauncherPrefs.get(preference.context)
                val current = launcherPrefs.get(LauncherPrefs.DOCK_ICONS)
                preference as SwitchPreferenceCompat
                preference.isChecked = current != 0
                updateDockIconsVisibility(current != 0)
                preference.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    val value = if (enabled) launcherPrefs.get(LauncherPrefs.GRID_WIDTH) else 0
                    launcherPrefs.put(LauncherPrefs.DOCK_ICONS, value)
                    if (enabled) findPreference<CustomSeekBarPreference>(DOCK_ICONS)?.setValue(value)
                    updateDockIconsVisibility(enabled)
                    true
                }
                return true
            }
            DOCK_ICONS -> {
                preference as CustomSeekBarPreference
                preference.min = if (LauncherPrefs.DOCK_ICONS.get(requireContext()) == 0) 0 else 3
                return true
            }
            TASKBAR -> {
                // Turning it on asks for the accessibility access its window needs.
                preference.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requestAccessibilityPermission(requireContext())
                    true
                }
                return true
            }
            LOCK_HOME_SCREEN -> {
                val addIcon = findPreference<Preference>(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY)
                addIcon?.isEnabled = !(preference as SwitchPreferenceCompat).isChecked
                preference.setOnPreferenceChangeListener { _, newValue ->
                    addIcon?.isEnabled = !(newValue as Boolean)
                    true
                }
                return true
            }
            SMARTSPACE_MODE -> {
                preference as RadioGroupPreference
                preference.asEnum(SmartspaceMode::class.java).apply {
                    setDefaultValue(LauncherPrefs.SMARTSPACE_MODE.defaultValue)
                    setTextProvider { c, mode -> mode.getDisplayName(c) }
                    setIconProvider { c, mode -> AppCompatResources.getDrawable(c, mode.iconRes) }
                    setSummaryProvider { c, mode -> mode.getSummary(c) }
                    setEnabledProvider { c, mode ->
                        if (mode == SmartspaceMode.GOOGLE_SMARTSPACE) isGoogleSmartspaceAvailable.value
                        else true
                    }
                    setOnPreferenceChangeListener { value ->
                        val ctx = requireContext()
                        val oldMode = LauncherPrefs.SMARTSPACE_MODE.get(ctx)
                        if (value == SmartspaceMode.DISABLED && oldMode != SmartspaceMode.DISABLED)
                            LauncherPrefs.get(ctx).put(LauncherPrefs.PENDING_SMARTSPACE_REMOVAL, true)
                        true
                    }
                }
                return true
            }
            else -> return true
        }
    }

    /**
     * Wires a gesture-action `RadioGroupPreference` exactly like
     * SMARTSPACE_MODE's own `RadioGroupPreference` above -- one shared
     * function since both gesture slots (double-tap, swipe-down) offer
     * the same GestureAction choices.
     */
    private fun updateDockIconsVisibility(enabled: Boolean) {
        findPreference<CustomSeekBarPreference>(DOCK_ICONS)?.apply {
            isEnabled = enabled
            isVisible = enabled
            min = if (enabled) 3 else 0
        }
    }

    private fun bindGestureAction(preference: RadioGroupPreference, slot: ConstantItem<GestureAction>) {
        preference.asEnum(GestureAction::class.java).apply {
            setDefaultValue(slot.defaultValue)
            setTextProvider { c, action -> action.getDisplayName(c) }
            // A slot that opens an app names the app.
            setSummaryProvider { c, action ->
                if (action == GestureAction.LAUNCH_APP) HomeActions.label(c, HomeActions.slotAction(c, slot)) else action.getSummary(c)
            }
            // Pre-P devices lock the screen through a device admin
            // (GestureAction.LOCK_SCREEN's own legacy path), which needs
            // requesting once when the action is chosen; P+ (every real target device) uses the
            // accessibility service instead and needs nothing here.
            setOnPreferenceChangeListener { newValue ->
                // "Open an app" asks which first; the slot changes only once one is picked.
                if (newValue == GestureAction.LAUNCH_APP) {
                    HomeActions.pickApp(requireContext(), getString(R.string.home_action_pick_app)) { app ->
                        HomeActions.setSlot(preference.context, slot, HomeAction.Launch(app))
                        updatePreferenceView()
                    }
                    return@setOnPreferenceChangeListener false
                }
                if (newValue == GestureAction.LOCK_SCREEN && !Utilities.ATLEAST_P) {
                    val dpm = requireContext().getSystemService(DevicePolicyManager::class.java)
                    val admin = ComponentName(requireContext(), ScreenOffAdminReceiver::class.java)
                    if (dpm != null && !dpm.isAdminActive(admin)) {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        }
                        startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
                        // Still accept the selection: the gesture simply
                        // no-ops until admin is granted, matching what
                        // lockScreenLegacy() already did unconditionally.
                    }
                }
                true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // No preference to flip back off any more (see bindGestureAction's
        // own comment): a denied request just leaves LOCK_SCREEN selected
        // but a no-op, same as it always was with no admin active.
    }
}