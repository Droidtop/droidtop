package dev.droidtop.runtime.systemstatus

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
import dev.droidtop.runtime.tasks.TaskManager

/**
 * Android's own accessibility settings for every app (docs/SPEC.md "The companion's tabs", Accessibility;
 * Droidtop/tracker#414 slice C22): font size, display size, magnification, high-contrast text, colour inversion and
 * TalkBack. With the helper app and Settings > Risky actions > "Change accessibility settings" droidtop writes them
 * (`settings put`, `wm density`); otherwise each row opens Android's own screen. Turning TalkBack or magnification off
 * asks first, the safe answer first: someone who relies on them should not lose them by a stray tap. Catalog items,
 * so the companion's Accessibility card and the Quick Menu's Display section draw the same ones.
 */
object AccessibilityControls {
    const val ID_FONT = "pref_a11y_font_size"
    const val ID_DISPLAY_SIZE = "pref_a11y_display_size"
    const val ID_MAGNIFICATION = "pref_a11y_magnification"
    const val ID_CONTRAST = "pref_a11y_high_contrast"
    const val ID_INVERSION = "pref_a11y_inversion"
    const val ID_TALKBACK = "pref_a11y_talkback"

    val FONT_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f)

    /** Display size as a factor of the panel's own density, Android's Small to Largest. */
    val DISPLAY_FACTORS = listOf(0.85f to "Small", 1.0f to "Default", 1.1f to "Large", 1.2f to "Larger", 1.3f to "Largest")

    const val TALKBACK = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    /** "Physical density: 420" and an optional "Override density: 480" from `wm density`. Pure. */
    fun parseDensity(text: String): Pair<Int?, Int?> =
        Regex("Physical density: (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() to
            Regex("Override density: (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** The density for a display-size [factor] of the panel's [physical] density (Android rounds to whole dpi). Pure. */
    fun densityFor(physical: Int, factor: Float): Int = Math.round(physical * factor)

    /** The display-size factor closest to [current] of [physical]. Pure. */
    fun factorOf(physical: Int, current: Int): Float = DISPLAY_FACTORS.minByOrNull { kotlin.math.abs(densityFor(physical, it.first) - current) }!!.first

    /** The enabled-services list with TalkBack added or taken out, everything else kept. Pure. */
    fun withTalkBack(services: String?, on: Boolean): String {
        val list = services.orEmpty().split(':').filter { it.isNotBlank() && it != TALKBACK }
        return (if (on) list + TALKBACK else list).joinToString(":")
    }

    fun talkBackOn(services: String?): Boolean = services.orEmpty().split(':').any { it.equals(TALKBACK, ignoreCase = true) }

    /** Whether droidtop may write these now: the helper app and the Risky actions class. Blocks. */
    fun canWrite(): Boolean =
        RiskyActions.allows(RiskyClass.ACCESSIBILITY_SETTINGS) && runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)

    private fun put(namespace: String, key: String, value: String): String? {
        val out = TaskManager.shell.exec(listOf("settings", "put", namespace, key, value)) ?: return "The helper app did not answer"
        return if (out.exit == 0) null else out.stderr.trim().ifEmpty { "Android refused" }
    }

    private fun secureOn(context: Context, key: String) = runCatching { Settings.Secure.getInt(context.contentResolver, key) == 1 }.getOrDefault(false)

    private fun opener(id: String, title: String, subtitle: String, action: String) =
        ActionItem(id = id, title = title, subtitle = subtitle, value = "In Android's settings", run = { ctx -> SettingsLaunch.start(ctx, Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })

    /** The rows, as the helper app and the Risky actions class allow. Reads settings: off the main thread. */
    fun items(context: Context): List<CatalogItem> {
        if (!canWrite()) {
            val hint = if (runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)) {
                ". " + RiskyPrompts.turnOnHint(RiskyClass.ACCESSIBILITY_SETTINGS)
            } else {
                ""
            }
            return listOf(
                opener(ID_FONT, "Font size", "Text size in every app$hint", Settings.ACTION_DISPLAY_SETTINGS),
                opener(ID_DISPLAY_SIZE, "Display size", "How big everything on screen is$hint", Settings.ACTION_DISPLAY_SETTINGS),
                opener(ID_TALKBACK, "TalkBack, magnification, contrast and colours", "Android's accessibility settings$hint", Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
        }
        val font = runCatching { Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE) }.getOrDefault(1f)
        val (physical, override) = TaskManager.shell.exec(listOf("wm", "density"))?.stdout?.let(::parseDensity) ?: (null to null)
        val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val talkBack = talkBackOn(services)
        val magnification = secureOn(context, "accessibility_display_magnification_enabled")
        fun switch(id: String, title: String, subtitle: String, on: Boolean, key: String, askOff: String?) = AsyncActionItem(
            id = id,
            title = title,
            subtitle = subtitle,
            value = if (on) "On" else "Off",
            confirmTitle = if (on) askOff else null,
            run = { _, _ -> put("secure", key, if (on) "0" else "1") ?: if (on) "Off" else "On" },
        )
        return buildList {
            add(
                ChoiceItem(
                    id = ID_FONT,
                    title = "Font size",
                    subtitle = "Text size in every app",
                    options = FONT_SCALES.map { ChoiceOption(it.toString(), "${(it * 100).toInt()}%" + if (it == 1.0f) " (default)" else "") },
                    current = FONT_SCALES.minByOrNull { kotlin.math.abs(it - font) }.toString(),
                    onSelect = { _, value -> Thread { put("system", "font_scale", value) }.start() },
                ),
            )
            if (physical != null) {
                add(
                    ChoiceItem(
                        id = ID_DISPLAY_SIZE,
                        title = "Display size",
                        subtitle = "How big everything on screen is, in every app",
                        options = DISPLAY_FACTORS.map { (factor, label) -> ChoiceOption(factor.toString(), label) },
                        current = factorOf(physical, override ?: physical).toString(),
                        onSelect = { _, value ->
                            val factor = value.toFloatOrNull() ?: 1f
                            Thread {
                                if (factor == 1f) TaskManager.shell.exec(listOf("wm", "density", "reset"))
                                else TaskManager.shell.exec(listOf("wm", "density", densityFor(physical, factor).toString()))
                            }.start()
                        },
                    ),
                )
            }
            add(switch(ID_MAGNIFICATION, "Magnification", "Triple-tap the screen to zoom in", magnification, "accessibility_display_magnification_enabled", "Turn magnification off? Triple-tap will no longer zoom in."))
            add(switch(ID_CONTRAST, "High-contrast text", "Text in black or white for every app", secureOn(context, "high_text_contrast_enabled"), "high_text_contrast_enabled", null))
            add(switch(ID_INVERSION, "Colour inversion", "Dark becomes light and light dark, in every app", secureOn(context, "accessibility_display_inversion_enabled"), "accessibility_display_inversion_enabled", null))
            add(
                AsyncActionItem(
                    id = ID_TALKBACK,
                    title = "TalkBack",
                    subtitle = "Android's screen reader speaks what is on screen",
                    value = if (talkBack) "On" else "Off",
                    confirmTitle = if (talkBack) "Turn TalkBack off? The screen stops being read aloud." else null,
                    run = { ctx, _ ->
                        val now = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        val next = !talkBackOn(now)
                        val services = withTalkBack(now, next)
                        val written = if (services.isEmpty()) {
                            TaskManager.shell.exec(listOf("settings", "delete", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))?.takeIf { it.exit == 0 }?.let { null } ?: "Android refused"
                        } else {
                            put("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                        }
                        (written ?: put("secure", Settings.Secure.ACCESSIBILITY_ENABLED, if (services.isNotEmpty()) "1" else "0"))
                            ?: if (next) "On" else "Off"
                    },
                ),
            )
        }
    }
}
