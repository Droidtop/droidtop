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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import dev.droidtop.library.theme.OutsideGamingColors
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.notices.ColorMath
import dev.droidtop.library.theme.ThemeSafeMode
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.ui.shared.NoticePalette
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * **Where it applies.** The whole palette, typefaces and texture included,
 * only while a Gaming surface is on screen: [GamingTheme] holds it active
 * between its host's start and stop. Everywhere else the tokens read the
 * colours outside Gaming ([OutsideGamingColors], docs/SPEC.md 7k "Colours
 * outside Gaming"): the Gaming theme's colours by default, or the Custom
 * droidtop theme. Desktop holds the whole palette too, unless Custom is chosen.
 *
 * **Cost.** The theme is parsed and mapped once per (theme, colour
 * scheme, variant, aspect ratio) on a background thread and cached; the
 * main thread only ever reads the finished palette.
 */
@Immutable
class GamingPalette(
    val colors: GamingThemeColors,
    val bodyFamily: FontFamily?,
    val displayFamily: FontFamily?,
    val textureBrush: Brush?,
    /** The theme's UI font has no lower case (its small letters are drawn as capitals): see [withFamilies]. */
    val bodyFamilyCapsOnly: Boolean = false,
    /**
     * The theme's views (`system`, `gamelist`) that draw their own clock or system status: on those the shell's
     * floating cluster yields (docs/SPEC.md 7k2, Droidtop/tracker#658).
     */
    val ownStatusViews: Set<String> = emptySet(),
    /**
     * High contrast is on (droidtop's own Accessibility setting, or Android's high-contrast text): text and the
     * notice colours hold 7:1, the ring is 4dp, there is no texture or art behind text (docs/SPEC.md 7k).
     */
    val highContrast: Boolean = false,
    /** Labels drawn in a theme's capitals are drawn as written ([labelCase]); always so under [highContrast]. */
    val sentenceCase: Boolean = false,
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
    val affirmative = Color(colors.affirmative)
    val favourite = Color(colors.favourite)
    val rowFill = Color(colors.rowFill)
    val rowFillSelected = Color(colors.rowFillSelected)
    val cardOutline = Color(colors.cardOutline)
    val hintPillOutline = Color(colors.hintPillOutline)
    val selected = Color(colors.selected)
    val onSelected = Color(colors.onSelected)
    val scrim = Color(colors.scrim)
    val launch = Color(colors.launch)
    val launchFocused = Color(colors.launchFocused)
    val launchDisabled = Color(colors.launchDisabled)
    val onLaunchMuted = Color(colors.onLaunchMuted)

    /**
     * The notice colours for this palette (docs/SPEC.md 7k, "Notices"), derived from the same theme
     * colours. Built on the palette's background thread ([GamingTheme.refresh]) before the palette is
     * published, so no composition pays for it.
     */
    val notice: NoticePalette by lazy {
        if (!colors.declared && !highContrast && !colors.isLight) NoticePalette.Default else NoticePalette.of(colors, highContrast)
    }

    /**
     * The Material scheme for code that reads [MaterialTheme]: droidtop's
     * own pair when no theme declares anything, otherwise the palette's
     * roles in a light or dark scheme to match the theme's ground.
     */
    val colorScheme: ColorScheme by lazy {
        // Material's error roles are the notice palette's danger, so a stock component that reads them
        // draws what the notice card draws.
        val n = notice.colors
        val errorRoles = { scheme: ColorScheme ->
            scheme.copy(
                error = Color(n.dangerInk),
                onError = Color(ColorMath.pickOn(n.dangerInk)),
                errorContainer = Color(n.dangerPlate),
                onErrorContainer = Color(n.onDanger),
            )
        }
        if (!colors.declared) {
            errorRoles(if (colors.isLight) ChromeColors.Light else ChromeColors.Dark)
        } else errorRoles(run {
            val outline = Color(ColorMath.over(colors.cardOutline, colors.ground))
            val onFavourite = Color(ColorMath.pickOn(colors.favourite))
            if (colors.isLight) {
                lightColorScheme(
                    background = ground, onBackground = onSurface,
                    surface = overlaySurface, onSurface = onSurface,
                    surfaceVariant = cardFocused, onSurfaceVariant = onSurfaceMuted,
                    primary = accent, onPrimary = onAccent,
                    tertiary = favourite, onTertiary = onFavourite,
                    outline = outline,
                )
            } else {
                darkColorScheme(
                    background = ground, onBackground = onSurface,
                    surface = overlaySurface, onSurface = onSurface,
                    surfaceVariant = cardFocused, onSurfaceVariant = onSurfaceMuted,
                    primary = accent, onPrimary = onAccent,
                    tertiary = favourite, onTertiary = onFavourite,
                    outline = outline,
                )
            }
        })
    }

    /** droidtop's type scale in the theme's own typefaces; the scale itself is unchanged. */
    val typography: Typography by lazy { DroidtopTypography.withFamilies(bodyFamily, displayFamily, bodyFamilyCapsOnly) }

    companion object {
        val Default = GamingPalette(GamingThemeColors.DEFAULT, null, null, null)

        /** [Default]'s light twin, for the Light appearance (docs/SPEC.md 7k, "Light and dark"). */
        val DefaultLight = GamingPalette(GamingThemeMapping.defaultFor(light = true), null, null, null)

        fun defaultFor(light: Boolean): GamingPalette = if (light) DefaultLight else Default
    }
}

/**
 * Whether a font draws no lower case: every small letter as wide as its capital, in a face that is not monospaced
 * ([width] measures one character). A theme font like that (Art Book Next's) is fine for headings and labels but turns
 * a URL, a plugin's name or a version into capitals, so the roles that carry data keep droidtop's own face
 * (Droidtop/tracker#532). Pure, for the tests.
 */
fun isCapsOnlyFont(width: (Char) -> Float): Boolean =
    ('a'..'z').all { width(it) == width(it.uppercaseChar()) } && width('i') != width('m')

/**
 * droidtop's type scale in the theme's faces. The theme's body face goes to the label and title roles (the chrome), and
 * to the data roles (a row's name, its value, prose) too unless it is [capsOnly]: then those keep droidtop's own face, so
 * data is never shown in capitals the person did not write.
 */
fun Typography.withFamilies(body: FontFamily?, display: FontFamily?, capsOnly: Boolean = false): Typography {
    if (body == null && display == null) return this
    fun TextStyle.inFamily(family: FontFamily?) = if (family == null) this else copy(fontFamily = family)
    val data = if (capsOnly) null else body
    return copy(
        displayLarge = displayLarge.inFamily(display),
        displayMedium = displayMedium.inFamily(display),
        displaySmall = displaySmall.inFamily(display),
        headlineLarge = headlineLarge.inFamily(display),
        headlineMedium = headlineMedium.inFamily(display),
        headlineSmall = headlineSmall.inFamily(display),
        titleLarge = titleLarge.inFamily(body),
        titleMedium = titleMedium.inFamily(data),
        titleSmall = titleSmall.inFamily(body),
        bodyLarge = bodyLarge.inFamily(data),
        bodyMedium = bodyMedium.inFamily(data),
        bodySmall = bodySmall.inFamily(data),
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

    /**
     * Whether droidtop draws light now (AppearancePrefs), as the last composition that resolved it found it
     * ([rememberLightAppearance]): what droidtop's own palette is when no theme is held.
     */
    private var appearanceLight by mutableStateOf(false)

    /** The colours outside Gaming as a palette, once [OutsideGamingColors] has published them in this process. */
    private var outside by mutableStateOf<GamingPalette?>(null)
    private val following = java.util.concurrent.atomic.AtomicBoolean(false)

    /** What a Gaming surface draws with right now: the theme's palette while one is on screen, [outsidePalette] otherwise. */
    val palette: GamingPalette get() = if (holders > 0) current else outsidePalette

    /**
     * What everything outside Gaming draws with (docs/SPEC.md 7k, "Colours outside Gaming"): the colours
     * [OutsideGamingColors] resolves, without the theme's typefaces or texture; droidtop's own palette for the
     * appearance until they are first resolved.
     */
    val outsidePalette: GamingPalette get() = outside ?: GamingPalette.defaultFor(appearanceLight)

    /**
     * Follows [OutsideGamingColors] for the rest of the process, once; every root calls it ([rememberLightAppearance]).
     * Each palette, its notice colours and Material scheme are built off the main thread and then published.
     */
    fun followOutside(context: Context) {
        if (!following.compareAndSet(false, true)) return
        val app = context.applicationContext
        dev.droidtop.runtime.util.AppScopes.main.launch {
            OutsideGamingColors.colors(app)
                .map { colors ->
                    when (colors) {
                        GamingPalette.Default.colors -> GamingPalette.Default
                        GamingPalette.DefaultLight.colors -> GamingPalette.DefaultLight
                        else -> GamingPalette(colors, null, null, null).also { it.notice; it.colorScheme }
                    }
                }
                .flowOn(Dispatchers.Default)
                .collect { outside = it }
        }
    }

    fun setAppearance(light: Boolean) {
        if (appearanceLight != light) appearanceLight = light
    }

    fun acquire() {
        holders++
        dev.droidtop.library.theme.RoleTokens.checkSoon()
    }

    fun release() {
        if (holders > 0) holders--
        dev.droidtop.library.theme.RoleTokens.checkSoon()
    }

    init {
        // The role tokens UI droidtop does not draw reads (docs/SPEC.md 7k, Droidtop/tracker#559): the theme's while a
        // Gaming surface holds one; otherwise droidtop's own for the appearance, which RoleTokens works out itself.
        dev.droidtop.library.theme.RoleTokens.provider = {
            if (holders > 0) current.let { dev.droidtop.library.theme.RoleTokens.of(it.colors, it.highContrast) } else null
        }
    }

    private data class PaletteKey(
        val theme: String?,
        val colorScheme: String?,
        val variant: String?,
        val aspectRatio: String?,
        val screenRatio: Float,
        val safeMode: Boolean,
        val highContrast: Boolean,
        val sentenceCase: Boolean,
        val light: Boolean,
    )

    private val cache = ConcurrentHashMap<PaletteKey, GamingPalette>()

    init {
        // A theme selection, a colour scheme or variant change, or a theme
        // re-downloaded in place all land here; the key already tells
        // them apart except for the last, which changes files under one name.
        LibraryThemePrefs.addOnChangeListener { cache.clear() }
    }

    /** Recomputes the palette off the main thread and publishes it. Cheap when the key is cached. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val next = withContext(Dispatchers.Default) {
            // droidtop's own palette's notices and scheme too: Default is drawn until the colours outside Gaming resolve.
            GamingPalette.Default.colorScheme
            GamingPalette.DefaultLight.colorScheme
            paletteFor(app)
        }
        if (next !== current) current = next
        dev.droidtop.library.theme.RoleTokens.checkSoon()
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
            highContrast = dev.droidtop.library.theme.ThemeColorStore.highContrast(context),
            sentenceCase = dev.droidtop.library.settings.ReadingPrefs.sentenceCase(context),
            light = dev.droidtop.library.settings.AppearancePrefs.light(context),
        )
        cache[key]?.let { return it }
        val theme = if (name == null) null else ThemeAssets.loadActiveTheme(context)
        // A failed parse is not remembered: it would stand for the whole
        // process the way ThemeAssets itself refuses to cache one.
        if (name != null && theme == null) return GamingPalette.defaultFor(key.light)
        val highContrast = key.highContrast
        val sentenceCase = key.sentenceCase || highContrast
        // A colour scheme the person named is drawn as the theme wrote it. Otherwise ThemeAssets loaded the scheme
        // matching the appearance, and a theme with none gets its palette derived for it (docs/SPEC.md 7k, "Light and dark").
        val colors = GamingThemeMapping.resolved(theme, key.light, schemeNamed = key.colorScheme != null, highContrast = highContrast)
        val ownStatusViews = GamingThemeMapping.ownStatusViews(theme)
        val palette = when {
            !colors.declared && ownStatusViews.isEmpty() && !highContrast && !sentenceCase -> GamingPalette.defaultFor(key.light)
            !colors.declared -> GamingPalette(colors, null, null, null, ownStatusViews = ownStatusViews, highContrast = highContrast, sentenceCase = sentenceCase)
            else -> {
                val bodyFace = loadTypeface(colors.bodyFontPath)
                GamingPalette(
                    colors = colors,
                    bodyFamily = bodyFace?.let { FontFamily(it) },
                    displayFamily = loadTypeface(colors.displayFontPath)?.let { FontFamily(it) },
                    // No texture behind text under High contrast: a plain ground.
                    textureBrush = if (highContrast) null else loadTexture(colors.textureImagePath),
                    bodyFamilyCapsOnly = bodyFace?.let { face ->
                        val paint = android.graphics.Paint().apply { typeface = face }
                        isCapsOnlyFont { paint.measureText(it.toString()) }
                    } == true,
                    ownStatusViews = ownStatusViews,
                    highContrast = highContrast,
                    sentenceCase = sentenceCase,
                )
            }
        }
        // Derived here, off the main thread, so the first composition that draws a notice reads a finished value.
        palette.notice
        palette.colorScheme
        cache[key] = palette
        return palette
    }

    /** A theme's font; a file that is not one is logged by its path and the theme draws in the default face. */
    private fun loadTypeface(path: String?): android.graphics.Typeface? {
        if (path == null) return null
        return try {
            android.graphics.Typeface.createFromFile(path)
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "theme font $path", e)
            null
        }
    }

    /**
     * A theme's background texture, decoded no larger than [TEXTURE_MAX_EDGE] on a side. Out of memory halves it
     * again, down to [TEXTURE_MIN_EDGE]; a file that does not decode is logged by its path and the theme draws flat.
     */
    private fun loadTexture(path: String?): Brush? {
        if (path == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, bounds)
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "theme texture $path", e)
            return null
        }
        var sample = 1
        while (bounds.outWidth / sample > TEXTURE_MAX_EDGE || bounds.outHeight / sample > TEXTURE_MAX_EDGE) sample *= 2
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample >= TEXTURE_MIN_EDGE) {
            try {
                val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
            } catch (e: OutOfMemoryError) {
                android.util.Log.w(LOG_TAG, "theme texture $path at 1/$sample: out of memory, trying smaller")
                sample *= 2
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "theme texture $path", e)
                return null
            }
        }
        return null
    }

    private const val TEXTURE_MAX_EDGE = 1024
    private const val TEXTURE_MIN_EDGE = 64
    private const val LOG_TAG = "droidtop.theme"
}

/**
 * Wrap a Gaming surface in this and it draws in the active ES-DE theme:
 * the palette is held active while the surface is started, kept current
 * as the theme, its colour scheme, variant or the screen's shape change,
 * and handed to [MaterialTheme] for code that reads that instead of
 * [MenuTokens]. Safe to nest and to use from every Activity the Gaming
 * mode opens; they share one palette.
 *
 * [outsideGaming] is for a surface outside Gaming that wears the Gaming theme
 * (Desktop): it holds the theme only while the colours outside Gaming match
 * it, and draws the Custom droidtop theme when that is chosen (docs/SPEC.md
 * 7k, "Colours outside Gaming").
 */
@Composable
fun GamingTheme(outsideGaming: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    val custom by OutsideGamingColors.customChosen.collectAsState()
    val holds = !(outsideGaming && custom)
    DisposableEffect(owner, holds) {
        if (!holds) {
            onDispose { }
        } else if (owner == null) {
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
    // High contrast and Sentence case (Settings > Accessibility) re-read the palette as soon as they change. The
    // listener is held here, in the composition, because SharedPreferences holds its listeners weakly.
    DisposableEffect(context) {
        val prefs = dev.droidtop.library.settings.CatalogPrefs.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in dev.droidtop.library.settings.ReadingPrefs.PALETTE_KEYS) ShellThemePrefs.bump()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    rememberLightAppearance()
    LaunchedEffect(ShellThemePrefs.version, configuration.screenWidthDp, configuration.screenHeightDp, configuration.uiMode) {
        GamingTheme.refresh(context)
    }
    val palette = GamingTheme.palette
    CompositionLocalProvider(LocalNoticePalette provides palette.notice) {
        MaterialTheme(colorScheme = palette.colorScheme, typography = palette.typography) {
            // No full-screen input method over droidtop's own fields (Droidtop/tracker#485 comment 6224857113).
            ProvideShellCanvas { dev.droidtop.ui.shared.NoFullscreenTextInput(content) }
        }
    }
}

/**
 * Whether droidtop draws light now (docs/SPEC.md 7k, "Light and dark"): the Appearance setting, with Follow Android read
 * from this composition's configuration, kept current as either changes. Every root that draws droidtop's UI calls it
 * (the Gaming theme, DroidtopTheme), and it hands the answer to [GamingTheme] so droidtop's own palette matches when no
 * theme is held. SharedPreferences holds listeners weakly, so the listener is held here, in the composition.
 */
@Composable
fun rememberLightAppearance(): Boolean {
    val context = LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    var choice by remember(context) { mutableStateOf(dev.droidtop.library.settings.AppearancePrefs.choice(context)) }
    DisposableEffect(context) {
        val prefs = dev.droidtop.library.settings.CatalogPrefs.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == dev.droidtop.library.settings.AppearancePrefs.KEY_APPEARANCE) {
                choice = dev.droidtop.library.settings.AppearancePrefs.choice(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val light = dev.droidtop.library.settings.AppearancePrefs.light(choice, uiMode)
    SideEffect {
        GamingTheme.setAppearance(light)
        GamingTheme.followOutside(context)
    }
    return light
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

/**
 * A label a theme draws in capitals (a section label, a tab, a chip, an eyebrow), as the palette says: in
 * capitals, or as written when Sentence case or High contrast is on (docs/SPEC.md 7k, "High contrast").
 * Never for data a person typed or a game's own name (tracker#532: theme transforms never apply to data).
 */
fun String.labelCase(): String = dev.droidtop.library.settings.ReadingPrefs.caps(this, GamingTheme.palette.sentenceCase)
