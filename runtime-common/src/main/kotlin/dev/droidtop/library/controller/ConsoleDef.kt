package dev.droidtop.library.controller

import android.content.Context

/** One value of a console's layout toggle: what the system does to the pad's face keys while the property holds it. */
data class ToggleValue(val keysSwapped: Boolean, val confirmOnRight: Boolean)

/** A system property that changes the face-button layout while the device runs, and what each of its values means. */
data class LayoutToggle(val property: String, val values: Map<String, ToggleValue>)

/** The built-in pad's identity: its ids or its name are enough to say a given input device is it. */
data class BuiltInPad(val name: String?, val vendorId: Int?, val productId: Int?) {
    fun matches(deviceName: String?, vendor: Int, product: Int): Boolean =
        (vendorId != null && productId != null && vendorId == vendor && productId == product) ||
            (name != null && deviceName != null && name.equals(deviceName.trim(), ignoreCase = true))
}

/** One row of the console table: the device, the pad built into it, what is printed on it and its layout toggle. */
data class ConsoleDef(
    val id: String,
    val name: String,
    val model: String,
    val manufacturer: String?,
    val pad: BuiltInPad?,
    val glyphFamily: GlyphFamily?,
    val toggle: LayoutToggle?,
    /** Where the add-on display sits: true above the built-in panel, false below, null when the row does not say. */
    val addonOnTop: Boolean? = null,
    /** The row's `displays[]` with a position: what names this device's screens by position (docs/SPEC.md 4c, "Screen names"). */
    val panels: List<dev.droidtop.runtime.ScreenPanel> = emptyList(),
) {
    fun matches(deviceManufacturer: String?, deviceModel: String?): Boolean =
        deviceModel != null && model.equals(deviceModel.trim(), ignoreCase = true) &&
            (manufacturer == null || manufacturer.equals(deviceManufacturer?.trim(), ignoreCase = true))
}

/**
 * Where the shell's input pipeline finds this device's row of the console table without depending on `:library-core`
 * (Droidtop/tracker#827 F1a): the table itself (`HardwareDatabase`, which fetches through the platform databases)
 * stays there, and droidtop installs it here at its start. Null until then, and on a device with no row.
 */
object ConsoleLookup {
    @Volatile
    var forThisDevice: ((Context) -> ConsoleDef?)? = null

    /** This device's row, or null. Reads a file: not for the main thread. */
    fun thisDevice(context: Context): ConsoleDef? = forThisDevice?.invoke(context)
}
