package dev.droidtop.library.theme

import android.content.Context
import android.content.SharedPreferences
import dev.droidtop.library.settings.AppearancePrefs
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.ReadingPrefs
import dev.droidtop.runtime.util.AppScopes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The selected Gaming theme's resolved colours, worked out once and kept (docs/SPEC.md 7k, "Colours outside Gaming";
 * owner, 2026-10-30, Droidtop/tracker#827): what Mobile, Desktop, Mobile's taskbar and the shared menus draw in by
 * default, whether or not Gaming has ever opened. The ES-DE theme is parsed and mapped
 * ([GamingThemeMapping.resolved]) on [AppScopes.io], never on the main thread, when its [Key] changes (the theme, its
 * colour scheme, variant or aspect ratio, Light or dark, High contrast, the tint) or a theme is re-downloaded in place; the answer
 * is written to its own preferences file with the key it was worked out for, so a later start reads it back instead of
 * parsing again. The screen's shape is not in the key: the colours a theme declares do not follow it.
 *
 * Read through [OutsideGamingColors], which also applies the colour source (Device colours, this, or the Custom
 * droidtop theme); nothing else reads this.
 */
object ThemeColorStore {
    private const val FILE = "droidtop_theme_colours"
    private const val KEY_KEY = "key"
    private const val KEY_COLORS = "colors"

    /** What the colours are worked out from: two equal keys give the same colours. */
    data class Key(
        val theme: String?,
        val colorScheme: String?,
        val variant: String?,
        val aspectRatio: String?,
        val light: Boolean,
        val highContrast: Boolean,
        /** "Tint themes to my colours": the accent the theme's accent and highlight become, or null (Droidtop/tracker#844). */
        val tint: Int? = null,
    ) {
        /** The key as it is stored beside the colours: a JSON array, so a missing value and the word "null" differ. Pure. */
        fun encode(): String = JsonArray(
            listOf(
                JsonPrimitive(theme), JsonPrimitive(colorScheme), JsonPrimitive(variant), JsonPrimitive(aspectRatio),
                JsonPrimitive(light), JsonPrimitive(highContrast), JsonPrimitive(tint),
            ),
        ).toString()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** [colors] as stored. Pure. */
    fun encode(colors: GamingThemeColors): String = json.encodeToString(GamingThemeColors.serializer(), colors)

    /** Stored colours back, or null when there are none or they are not readable (a field changed): worked out again then. Pure. */
    fun decode(text: String?): GamingThemeColors? =
        text?.let { runCatching { json.decodeFromString(GamingThemeColors.serializer(), it) }.getOrNull() }

    private val state = MutableStateFlow(GamingThemeColors.DEFAULT)

    /** The resolved colours; droidtop's own palette until the first read or resolve in this process has finished. */
    val colors: StateFlow<GamingThemeColors> get() = state

    /** A cheap read of [colors]; starts the first load if nothing has. Never touches disk on the caller's thread. */
    fun current(context: Context): GamingThemeColors {
        start(context)
        return state.value
    }

    private val started = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private val mutex = Mutex()
    @Volatile private var loaded = false
    @Volatile private var lastKey: Key? = null

    /** SharedPreferences holds listeners weakly, so the one listener is held here. */
    private var prefsWatch: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Reads the stored colours, or works them out when they are missing or stale, once per process, off the caller's thread. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        refreshSoon(context.applicationContext, force = false)
    }

    /**
     * The writer, from droidtop's process start (SettingsCatalogInitProvider): a theme selection, colour scheme, variant
     * or aspect ratio change, a theme re-downloaded in place or safe mode ([ThemePrefs]' listeners), Light or dark, High
     * contrast and the colours-outside-Gaming setting ([CatalogPrefs]), and Android's night mode (a configuration
     * change) each look again, off the main thread.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        start(app)
        if (!installed.compareAndSet(false, true)) return
        ThemePrefs.addOnChangeListener { refreshSoon(app, force = true) }
        prefsWatch = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in ReadingPrefs.PALETTE_KEYS || key in AppearancePrefs.OUTSIDE_GAMING_KEYS) refreshSoon(app, force = false)
        }.also { CatalogPrefs.prefs(app).registerOnSharedPreferenceChangeListener(it) }
        // A new wallpaper's colours (Device colours, Droidtop/tracker#844); the system palette itself arrives with the
        // configuration change below, which looks again too. The listener is Android 8.1's (API 27; minSdk is 26).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            try {
                app.getSystemService(android.app.WallpaperManager::class.java)?.addOnColorsChangedListener(
                    WallpaperColoursListener { refreshSoon(app, force = false) },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            } catch (e: Exception) {
                // No wallpaper service: the configuration change still looks again.
            }
        }
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = refreshSoon(app, force = false)

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    private fun refreshSoon(context: Context, force: Boolean) {
        AppScopes.io.launch { refresh(context, force) }
    }

    /** Brings [colors] up to date: from disk on the first look, else parsed when the key changed or [force]. Disk and parse work. */
    private suspend fun refresh(context: Context, force: Boolean) = mutex.withLock {
        val key = keyFor(context)
        if (!loaded) {
            loaded = true
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val stored = decode(prefs.getString(KEY_COLORS, null))
            if (stored != null && prefs.getString(KEY_KEY, null) == key.encode()) {
                state.value = stored
                lastKey = key
            }
        }
        if (force || key != lastKey) {
            val theme = if (key.theme == null) null else ThemeAssets.loadActiveTheme(context)
            if (key.theme != null && theme == null) {
                // A failed parse is not kept: the next look tries again, as ThemeAssets itself does.
                state.value = GamingThemeMapping.defaultFor(key.light)
                lastKey = null
            } else {
                write(context, key, GamingThemeMapping.resolved(theme, key.light, key.colorScheme != null, key.highContrast, key.tint))
            }
        }
        OutsideGamingColors.publish(context, state.value)
    }

    /** Keeps [colors] as the answer for [key], on disk and in memory. Disk work. */
    fun write(context: Context, key: Key, colors: GamingThemeColors) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_KEY, key.encode())
            .putString(KEY_COLORS, encode(colors))
            .apply()
        lastKey = key
        state.value = colors
    }

    /** The key for the active theme as things are now. Reads preferences and lists the themes: not the main thread's work. */
    fun keyFor(context: Context): Key {
        val name = ThemeAssets.activeThemeName(context)
        val light = AppearancePrefs.light(context)
        return Key(
            theme = name,
            colorScheme = name?.let { ThemePrefs.colorScheme(context, it) },
            variant = name?.let { ThemePrefs.variant(context, it) },
            aspectRatio = name?.let { ThemePrefs.aspectRatio(context, it) },
            light = light,
            highContrast = highContrast(context),
            tint = OutsideGamingColors.tintFor(context, light),
        )
    }

    /** High contrast: droidtop's own Accessibility setting, or Android's high-contrast text (docs/SPEC.md 7k). Reads preferences. */
    fun highContrast(context: Context): Boolean = highTextContrast(context) || ReadingPrefs.highContrast(context)

    /** Android's "High contrast text" accessibility switch; a Settings.Secure value with no public getter. */
    private fun highTextContrast(context: Context): Boolean =
        runCatching { android.provider.Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }
            .getOrDefault(false)
}

/**
 * The wallpaper colour listener as a class of its own because its interface is Android 8.1's and droidtop's minSdk is
 * 26: built only under the SDK_INT >= O_MR1 check in [ThemeColorStore.install] (build-scripts/class_load_api_allowlist.txt).
 * A lambda there is compiled into a class that implements the interface, which the class-load API gate refuses.
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O_MR1)
internal class WallpaperColoursListener(private val changed: () -> Unit) : android.app.WallpaperManager.OnColorsChangedListener {
    override fun onColorsChanged(colors: android.app.WallpaperColors?, which: Int) = changed()
}
