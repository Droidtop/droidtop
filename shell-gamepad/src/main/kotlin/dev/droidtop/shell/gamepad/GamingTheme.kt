package dev.droidtop.shell.gamepad

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.droidtop.library.theme.GamingThemeColors
import dev.droidtop.library.theme.GamingThemeMapping
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeColorMath
import dev.droidtop.library.theme.ThemeSafeMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.droidtop.library.theme.ThemePrefs as LibraryThemePrefs
import dev.droidtop.shell.gamepad.theme.ThemePrefs as ShellThemePrefs

/**
 * The active ES-DE theme, as the Gaming design tokens (docs/SPEC.md
 * "Gaming theming"): [GamingThemeMapping] reads the theme's own declared
 * colours, typefaces and background texture into [GamingThemeColors], and
 * this file is the Compose side of that one mapping.
 *
 * **How a screen gets the theme.** It does not ask. Every role in
 * [MenuTokens] reads [GamingTheme.palette], and the palette is snapshot
 * state, so any composition, draw or layout that reads a token is redrawn
 * when the theme changes -- Settings, Apps, the Quick Menu, menus and
 * dialogs, the header and the hint row all take their look from the same
 * lines they always did. The Material scheme and type scale are supplied
 * from the same palette by [GamingTheme] (the composable), for the code
 * that reads [MaterialTheme] instead. There is no second place a Gaming
 * surface can pick its colours from.
 *
 * **Where it applies.** Only while a Gaming surface is on screen:
 * [GamingTheme] holds the palette active between its host's start and
 * stop, so the standard launcher and the desktop, which share these
 * tokens' code, draw droidtop's own palette.
 *
 * **Cost.** The theme is parsed and mapped once per (theme, colour
 * scheme, variant, aspect ratio) on a background thread and cached; the
 * main thread only ever reads the finished palette.
 */
@Immutable
class GamingPalette internal constructor(
    val colors: GamingThemeColors,
    internal val bodyFamily: FontFamily?,
    internal val displayFamily: FontFamily?,
    internal val textureBrush: Brush?,
) {
    val ground = Color(colors.ground)
    val overlaySurface = Color(colors.overlaySurface)
    val card = Color(colors.card)
    val cardFocused = Color(colors.cardFocused)
    val cardInset = Color(colors.cardInset)
    val hintBar = Color(colors.hintBar)
    val onSurface = Color(colors.onSurface)
    val onSurfaceMuted = Color(colors.onSurfaceMuted)
    val value = Color(colors.value)
    val placeholder = Color(colors.placeholder)
    val sectionLabel = Color(colors.sectionLabel)
    val onSurfaceDisabled = Color(colors.onSurfaceDisabled)
    val accent = Color(colors.accent)
    val onAccent = Color(colors.onAccent)
    val danger = Color(colors.danger)
    val affirmative = Color(colors.affirmative)
    val favourite = Color(colors.favourite)
    val rowFill = Color(colors.rowFill)
    val rowFillSelected = Color(colors.rowFillSelected)
    val cardOutline = Color(colors.cardOutline)
    val hintPillOutline = Color(colors.hintPillOutline)
    val selected = Color(colors.selected)
    val onSelected = Color(colors.onSelected)
    val scrim = Color(colors.scrim)
    val dangerPlate = Color(colors.dangerPlate)
    val launch = Color(colors.launch)
    val launchFocused = Color(colors.launchFocused)
    val launchDisabled = Color(colors.launchDisabled)
    val onLaunchMuted = Color(colors.onLaunchMuted)

    /**
     * The Material scheme for code that reads [MaterialTheme]: droidtop's
     * own pair when no theme declares anything, otherwise the palette's
     * roles in a light or dark scheme to match the theme's ground.
     */
    val colorScheme: ColorScheme by lazy {
        if (!colors.declared) {
            ChromeColors.Dark
        } else {
            val outline = Color(ThemeColorMath.over(colors.cardOutline, colors.ground))
            val onFavourite = Color(ThemeColorMath.pickOn(colors.favourite))
            val onDanger = Color(ThemeColorMath.pickOn(colors.danger))
            if (colors.isLight) {
                lightColorScheme(
                    background = ground, onBackground = onSurface,
                    surface = overlaySurface, onSurface = onSurface,
                    surfaceVariant = cardFocused, onSurfaceVariant = onSurfaceMuted,
                    primary = accent, onPrimary = onAccent,
                    tertiary = favourite, onTertiary = onFavourite,
                    error = danger, onError = onDanger,
                    outline = outline,
                )
            } else {
                darkColorScheme(
                    background = ground, onBackground = onSurface,
                    surface = overlaySurface, onSurface = onSurface,
                    surfaceVariant = cardFocused, onSurfaceVariant = onSurfaceMuted,
                    primary = accent, onPrimary = onAccent,
                    tertiary = favourite, onTertiary = onFavourite,
                    error = danger, onError = onDanger,
                    outline = outline,
                )
            }
        }
    }

    /** droidtop's type scale in the theme's own typefaces; the scale itself is unchanged. */
    val typography: Typography by lazy { DroidtopTypography.withFamilies(bodyFamily, displayFamily) }

    companion object {
        val Default = GamingPalette(GamingThemeColors.DEFAULT, null, null, null)
    }
}

private fun Typography.withFamilies(body: FontFamily?, display: FontFamily?): Typography {
    if (body == null && display == null) return this
    fun TextStyle.inFamily(family: FontFamily?) = if (family == null) this else copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.inFamily(display),
        displayMedium = displayMedium.inFamily(display),
        displaySmall = displaySmall.inFamily(display),
        headlineLarge = headlineLarge.inFamily(display),
        headlineMedium = headlineMedium.inFamily(display),
        headlineSmall = headlineSmall.inFamily(display),
        titleLarge = titleLarge.inFamily(body),
        titleMedium = titleMedium.inFamily(body),
        titleSmall = titleSmall.inFamily(body),
        bodyLarge = bodyLarge.inFamily(body),
        bodyMedium = bodyMedium.inFamily(body),
        bodySmall = bodySmall.inFamily(body),
        labelLarge = labelLarge.inFamily(body),
        labelMedium = labelMedium.inFamily(body),
        labelSmall = labelSmall.inFamily(body),
    )
}

/**
 * The live palette, and the cache behind it. Held in one place so the
 * tokens (plain properties, read from anywhere) and the composable that
 * keeps it current agree on one value.
 */
object GamingTheme {
    private var current by mutableStateOf(GamingPalette.Default)
    private var holders by mutableIntStateOf(0)

    /** What a Gaming surface draws with right now: the theme's palette while one is on screen, droidtop's own otherwise. */
    val palette: GamingPalette get() = if (holders > 0) current else GamingPalette.Default

    internal fun acquire() { holders++ }
    internal fun release() { if (holders > 0) holders-- }

    private data class PaletteKey(
        val theme: String?,
        val colorScheme: String?,
        val variant: String?,
        val aspectRatio: String?,
        val screenRatio: Float,
        val safeMode: Boolean,
    )

    private val cache = ConcurrentHashMap<PaletteKey, GamingPalette>()

    init {
        // A theme selection, a colour scheme or variant change, or a theme
        // re-downloaded in place all land here; the key already tells
        // them apart except for the last, which changes files under one name.
        LibraryThemePrefs.addOnChangeListener { cache.clear() }
    }

    /** Recomputes the palette off the main thread and publishes it. Cheap when the key is cached. */
    internal suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val next = withContext(Dispatchers.Default) { paletteFor(app) }
        if (next !== current) current = next
    }

    private fun paletteFor(context: Context): GamingPalette {
        val name = ThemeAssets.activeThemeName(context)
        val metrics = context.resources.displayMetrics
        val key = PaletteKey(
            theme = name,
            colorScheme = name?.let { LibraryThemePrefs.colorScheme(context, it) },
            variant = name?.let { LibraryThemePrefs.variant(context, it) },
            aspectRatio = name?.let { LibraryThemePrefs.aspectRatio(context, it) },
            screenRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat(),
            safeMode = ThemeSafeMode.active,
        )
        cache[key]?.let { return it }
        val theme = if (name == null) null else ThemeAssets.loadActiveTheme(context)
        // A failed parse is not remembered: it would stand for the whole
        // process the way ThemeAssets itself refuses to cache one.
        if (name != null && theme == null) return GamingPalette.Default
        val colors = GamingThemeMapping.map(theme)
        val palette = if (!colors.declared) {
            GamingPalette.Default
        } else {
            GamingPalette(
                colors = colors,
                bodyFamily = loadFamily(colors.bodyFontPath),
                displayFamily = loadFamily(colors.displayFontPath),
                textureBrush = loadTexture(colors.textureImagePath),
            )
        }
        cache[key] = palette
        return palette
    }

    private fun loadFamily(path: String?): FontFamily? {
        if (path == null) return null
        return try {
            FontFamily(android.graphics.Typeface.createFromFile(path))
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadTexture(path: String?): Brush? {
        if (path == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > TEXTURE_MAX_EDGE || bounds.outHeight / sample > TEXTURE_MAX_EDGE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        } catch (t: Throwable) {
            null
        }
    }

    private const val TEXTURE_MAX_EDGE = 1024
}

/**
 * Wrap a Gaming surface in this and it draws in the active ES-DE theme:
 * the palette is held active while the surface is started, kept current
 * as the theme, its colour scheme, variant or the screen's shape change,
 * and handed to [MaterialTheme] for code that reads that instead of
 * [MenuTokens]. Safe to nest and to use from every Activity the Gaming
 * mode opens; they share one palette.
 */
@Composable
fun GamingTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(owner) {
        if (owner == null) {
            GamingTheme.acquire()
            onDispose { GamingTheme.release() }
        } else {
            var held = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        if (!held) {
                            held = true
                            GamingTheme.acquire()
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        if (held) {
                            held = false
                            GamingTheme.release()
                        }
                    }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                if (held) { held = false; GamingTheme.release() }
            }
        }
    }
    val configuration = LocalConfiguration.current
    LaunchedEffect(ShellThemePrefs.version, configuration.screenWidthDp, configuration.screenHeightDp) {
        GamingTheme.refresh(context)
    }
    val palette = GamingTheme.palette
    MaterialTheme(colorScheme = palette.colorScheme, typography = palette.typography, content = content)
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * The ground behind a Gaming page: the theme's ground colour, with the
 * theme's own background texture tiled over it where it declares one. The
 * one way a page paints its ground, so a texture reaches every page at once.
 *
 * The texture is multiplied by the ground colour, as ES-DE tints an image
 * with its `color`, and drawn at a reduced strength so it stays a
 * texture: the contrast the mapping guarantees is against the flat ground.
 */
fun Modifier.groundBackground(): Modifier = drawBehind {
    val palette = GamingTheme.palette
    drawRect(palette.ground)
    palette.textureBrush?.let { brush ->
        drawRect(
            brush = brush,
            alpha = TEXTURE_STRENGTH,
            colorFilter = ColorFilter.tint(palette.ground, BlendMode.Modulate),
        )
    }
}

private const val TEXTURE_STRENGTH = 0.6f
