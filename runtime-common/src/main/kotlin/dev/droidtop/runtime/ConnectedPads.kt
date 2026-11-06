package dev.droidtop.runtime

import android.content.Context
import android.os.Build
import android.view.InputDevice

/**
 * The controllers connected now and their batteries (`InputDevice.getBatteryState`, Android 12 and later; before that no
 * battery is reported): the companion's Input and Home lines, and Settings > Controls (Droidtop/tracker#723). Read
 * when a surface shows, never polled; an input-manager query, so off the main thread where it can be.
 */
object ConnectedPads {
    data class Pad(val name: String, val percent: Int?)

    fun read(context: Context): List<Pad> {
        val manager = context.getSystemService(android.hardware.input.InputManager::class.java) ?: return emptyList()
        return manager.inputDeviceIds.toList().mapNotNull { id ->
            val device = manager.getInputDevice(id) ?: return@mapNotNull null
            val pad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (!pad || device.isVirtual) return@mapNotNull null
            Pad(device.name, batteryPercent(device))
        }.distinctBy { it.name }
    }

    private fun batteryPercent(device: InputDevice): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val battery = device.batteryState
        if (!battery.isPresent) return null
        val capacity = battery.capacity
        return if (capacity.isNaN()) null else (capacity * 100).toInt().coerceIn(0, 100)
    }
}
