package dev.droidtop.shell.gamepad.theme

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Row
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.github.penfeizhou.animation.FrameAnimationDrawable
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.gif.GifDrawable
import com.github.penfeizhou.animation.loader.FileLoader
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.theme.BADGE_SLOTS
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import dev.droidtop.library.theme.esDeVideoFrame
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Real ES-DE `pos`/`size`/`fontSize`/etc. fractions are of the "screen"
 * (`Renderer::getScreenWidth/Height()`, confirmed directly against real
 * ES-DE source, e.g. `CarouselComponent.h`'s own `itemSize` parsing:
 * `mItemSize = itemSize * vec2(getScreenWidth(), getScreenHeight())`) --
 * but droidtop draws its own top tab bar (Games/Apps/Settings) above the
 * themed area, which has no real ES-DE equivalent and isn't part of any
 * theme's own coordinate space. [EsDeThemedView]'s own `BoxWithConstraints`
 * (its real, measured, actual-device size, already positioned below that
 * header by its caller) IS the one true "screen" every themed element's
 * fractions must resolve against -- provided here ONCE via
 * [CompositionLocalProvider] rather than threaded as an explicit
 * parameter through every nested composable (carousel/grid/textlist and
 * their own per-item renderers included), so nothing downstream can
 * silently fall back to a wrong reference (a hardcoded 1920x1080, or the
 * physical device screen including the header) the way a hand-threaded
 * optional parameter easily could. Null only outside any themed area at
 * all (the real "no active theme" fallback call sites) -- readers fall
 * back to the raw device screen via `LocalConfiguration` there, see
 * [dev.droidtop.shell.gamepad.theme.themedAreaSize].
 */
val LocalEsDeThemedAreaSize = compositionLocalOf<androidx.compose.ui.unit.DpSize?> { null }

/**
 * System-level bindings for `systemdata`-bound `text` elements --
 * transcribed from real `SystemView::updateGameCount`
 * (SystemView.cpp:966-1030, the actual source of every real format
 * below). [countsOnly] is that function's own favorites/recent special
 * case (`favoriteSystem`/`recentSystem`): those two auto-collections
 * show a bare "N games" with no favorites suffix, and their
 * gamecountGames/gamecountFavorites sub-bindings behave differently
 * (see [EsDeThemedText]'s systemdata branch).
 */
data class EsDeSystemContext(
    val name: String?,
    val gameCount: Int,
    val favoriteCount: Int,
    val countsOnly: Boolean,
)

/**
 * Renders a parsed [EsDeThemeView] as ONE coherent screen -- every
 * positioned element (background/video/info text/carousel/help/...)
 * composited together by real z-index, the theme itself driving the
 * layout. This replaces an earlier, real bug: [EsDeSystemListView] used
 * to be rendered separately, in droidtop's own hardcoded Column, ignoring
 * the theme's actual `pos`/`size`/`origin` for the carousel/grid/textlist
 * element entirely (DEcaffe positions it centered near the BOTTOM of the
 * screen; droidtop's own layout put system cards crowding the TOP,
 * nothing like the real theme). The primary list element is now just
 * another themed element type in this same dispatcher, positioned exactly
 * like every other one -- [items]/[firstItemFocus] are threaded through
 * only for that one case.
 */
@Composable
fun EsDeThemedView(
    view: EsDeThemeView,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    // Real per-system metadata (systemName/systemManufacturer/
    // systemReleaseYear/...) needs a theme parsed with THAT system's own
    // ${system.theme} substituted (see ThemeAssets.loadActiveTheme's own
    // doc comment) -- this bubbles the carousel/grid/textlist's own
    // focused-item index up to whoever owns [view] itself, so it can load
    // and pass down the right per-system-parsed EsDeThemeView. A no-op
    // default since most callers of this composable don't have a
    // per-system theme concept at all (e.g. anything that isn't the
    // "system" view).
    onFocusedIndexChanged: (Int) -> Unit = {},
    // The focused system's own real games (from the SAME LibraryEntry list
    // Games already shows elsewhere), feeding <gameselector>-driven
    // elements (the game-preview poster/mosaic/title in DEcaffe's real
    // "system" view) -- empty by default since most callers of this
    // composable (anything that isn't the "system" view) have no
    // gameselector elements to feed at all.
    focusedSystemEntries: List<LibraryEntry> = emptyList(),
    // Real, currently-relevant button hints for whatever screen [view] is
    // rendering (e.g. A/Select, Y/Info, L-R/Switch section) -- the theme's
    // own real <helpsystem> element (see EsDeTheme.kt's schema) only
    // supplies WHERE/HOW to draw them (pos/origin/colors/font/spacing);
    // WHICH actions are currently valid is app state this renderer has no
    // way to know on its own, same reason [items]/[firstItemFocus] are
    // threaded through for the list element rather than owned here.
    // Empty by default: most EsDeThemedView callers render a view with no
    // real helpsystem element at all.
    hints: List<kotlin.Pair<GamepadAction, String>> = emptyList(),
    // Real gamelist-view case, confirmed against the bundled DEcaffe theme
    // directly: its own real gamelist view declares NO <carousel>/<grid>/
    // <textlist> (see EsDeThemeView.primaryListElement's own updated doc
    // comment) AND NO <gameselector> either -- every metadata/image/video
    // element there just implicitly binds to index 0 with no selection
    // mechanism of its own. Real ES-DE itself always has an underlying
    // navigable per-game cursor regardless of whether the theme renders
    // any visual widget for it; a caller with no on-screen list widget to
    // delegate that to (see GamesSection's own headless gamelist-
    // navigation branch) passes the real, currently-focused index here.
    // Only takes effect when the view genuinely has no <gameselector> of
    // its own -- never overrides a real, theme-declared one (e.g. the
    // "system" view's random game-preview collage).
    focusedGameIndex: Int? = null,
    // System-level bindings for `systemdata` text elements (see
    // [EsDeSystemContext]) -- null for callers with no system concept.
    systemContext: EsDeSystemContext? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        val viewWidth = maxWidth
        val viewHeight = maxHeight
        CompositionLocalProvider(
            LocalEsDeThemedAreaSize provides androidx.compose.ui.unit.DpSize(viewWidth, viewHeight),
        ) {
        // Selected ONCE per focused-system change (remember's key is the
        // entries list itself -- structurally stable across recompositions
        // for the same system, changes when focus moves to a different
        // one), not re-randomized every frame -- see GameSelector's own
        // doc comment for why that distinction matters for a real
        // "stable until you change platform" game-preview collage.
        val gameSelector = view.elements.values.firstOrNull { it.type == "gameselector" }
        val gameCount = gameSelector?.valueOrNull<EsDeThemeValue.UInt>("gameCount")?.value?.toInt() ?: 1
        val allowDuplicates = gameSelector?.valueOrNull<EsDeThemeValue.Bool>("allowDuplicates")?.value ?: true
        val gameSelection = if (gameSelector == null && focusedGameIndex != null) {
            listOfNotNull(focusedSystemEntries.getOrNull(focusedGameIndex))
        } else {
            remember(focusedSystemEntries) {
                GameSelector.select(focusedSystemEntries, gameCount, allowDuplicates)
            }
        }
        view.elements.values
            // Real ES-DE `visible` property, applies to every element type
            // -- checked once here rather than duplicated in each
            // per-type renderer below.
            .filter { it.valueOrNull<EsDeThemeValue.Bool>("visible")?.value != false }
            .sortedBy { zIndexOf(it) }.forEach { element ->
            when (element.type) {
                "image" -> EsDeThemedImage(element, viewWidth, viewHeight, gameSelection)
                "text" -> EsDeThemedText(element, viewWidth, viewHeight, gameSelection, systemContext)
                // Real video playback (see EsDeThemedVideo's own doc
                // comment) when the selected game has a real, scraped
                // video file; the same static-poster fallback
                // EsDeThemedFallbackImage always used otherwise (no video
                // present, or a gameselector slot with no video concept at
                // all, e.g. a mosaic tile).
                "video" -> EsDeThemedVideo(element, viewWidth, viewHeight, gameSelection)
                // Real GIF/APNG frame-animation playback (see
                // EsDeThemedAnimation's own doc comment) -- no longer the
                // static-image fallback this used to be.
                "animation" -> EsDeThemedAnimation(element, viewWidth, viewHeight)
                // Not a visual element at all in real ES-DE -- `<sound>`
                // declarations feed NavigationSounds, not the render tree
                // (Sound.cpp's getFromTheme reads them by element lookup).
                // droidtop's equivalent consumer is EsDeNavigationSounds,
                // loaded alongside the theme itself, never drawn here.
                "sound" -> Unit
                // Real, live-rendered -- ES-DE's own "clock" type has no
                // "metadata" property at all (confirmed against its real
                // schema), unlike "datetime".
                "clock" -> EsDeThemedClock(element, viewWidth, viewHeight)
                // Real, now-unblocked: LibraryEntry.releaseDate exists
                // (see that field's own doc comment -- real per-game
                // metadata, scraped via ScreenScraper/TheGamesDB), so a
                // theme's own real <datetime metadata="releasedate"> can
                // finally bind to real data instead of parsing-but-never-
                // rendering. Genuinely different from "clock": this reads
                // one static value once, not a live-ticking wall clock.
                "datetime" -> EsDeThemedDateTime(element, viewWidth, viewHeight, gameSelection)
                // Real, now-unblocked the same way: LibraryEntry.rating
                // exists (0.0-1.0, same real convention real ES-DE's own
                // MD_RATING uses).
                "rating" -> EsDeThemedRating(element, viewWidth, viewHeight, gameSelection)
                // The real fix described above: positioned/sized exactly
                // like any other themed element, using the SAME EsDeCarousel/
                // EsDeGrid/EsDeTextList composables that already read the
                // theme's own itemSize/colors -- only the outer placement
                // was ever wrong.
                "carousel", "grid", "textlist" -> EsDeThemedListElement(
                    element, items, firstItemFocus, viewWidth, viewHeight, onFocusedIndexChanged,
                )
                // helpsystem is deliberately NOT dispatched per element --
                // see the singleton merge/render after this loop. Real,
                // confirmed-live bug the per-element dispatch caused: Art
                // Book Next declares MANY named <helpsystem> blocks
                // (help-system-view/help-gamelist-view/help-menu-view,
                // scope-gated), and real ES-DE's HelpComponent is a
                // SINGLETON the theme merely styles -- every declaration
                // configures the one component, with `scope` choosing
                // which config applies where. Rendering one bar per
                // element drew the help bar three times at once on a real
                // device.
                "helpsystem" -> Unit
                // Real, honestly PARTIAL -- see EsDeThemedBadges' own doc
                // comment for exactly which of real ES-DE's nine real
                // badge slot types this actually covers (one: favorite).
                "badges" -> EsDeThemedBadges(element, viewWidth, viewHeight, gameSelection)
                // Real, honestly PARTIAL -- see EsDeThemedSystemStatus'
                // own doc comment for which of real ES-DE's four real
                // entry types this covers (wifi/cellular/battery; not
                // bluetooth).
                "systemstatus" -> EsDeThemedSystemStatus(element, viewWidth, viewHeight)
                // Real, honestly PARTIAL -- see EsDeThemedGamelistInfo's
                // own doc comment. focusedSystemEntries (not gameSelection)
                // is the real total-count context this needs -- every game
                // in the currently browsed system, not just the one/few
                // gameSelector picked.
                "gamelistinfo" -> EsDeThemedGamelistInfo(element, viewWidth, viewHeight, focusedSystemEntries)
            }
        }
        // Real ES-DE HelpComponent semantics: ONE help bar per view,
        // configured by merging every declared <helpsystem> element (a
        // theme commonly declares several, multi-named/scope-gated -- Art
        // Book Next does) rather than drawn once per declaration.
        // `scope=menu` styles real ES-DE's own menu overlays, which
        // droidtop doesn't render at all -- those declarations are
        // skipped, not merged in (their pos/colors are for a different
        // surface entirely). Remaining declarations merge in document
        // order (LinkedHashMap preserves parse order; later wins per
        // property), matching real ES-DE's own last-applied-wins theme
        // application. Rendered after the element loop -- help draws on
        // top, real ES-DE's own draw order for it.
        val helpElements = view.elements.values.filter {
            it.type == "helpsystem" &&
                it.valueOrNull<EsDeThemeValue.Str>("scope")?.value != "menu"
        }
        if (helpElements.isNotEmpty() && hints.isNotEmpty()) {
            val merged = EsDeThemeElement(
                type = "helpsystem",
                key = "helpsystem_merged",
                properties = helpElements.fold(emptyMap()) { acc, element -> acc + element.properties },
            )
            if (merged.valueOrNull<EsDeThemeValue.Bool>("visible")?.value != false) {
                EsDeThemedHelpSystem(merged, viewWidth, viewHeight, hints)
            }
        }
        }
    }
}

@Composable
private fun EsDeThemedListElement(
    element: EsDeThemeElement,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    viewWidth: Dp,
    viewHeight: Dp,
    onFocusedIndexChanged: (Int) -> Unit,
) {
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    EsDeSystemListView(
        element = element,
        items = items,
        firstItemFocus = firstItemFocus,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY).size(width = width, height = height),
        onFocusedIndexChanged = onFocusedIndexChanged,
    )
}

@Composable
private fun EsDeThemedImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val selected = gameSelection.getOrNull(0)
    // Real `metadataElement` -- see esDeHiddenByMetadataFlag's own doc comment.
    if (esDeHiddenByMetadataFlag(element, selected)) return
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    // Real `scrollFadeIn` -- multiplies into the element's own real
    // opacity rather than replacing it, matching real ES-DE's own
    // setOpacity animation running on top of the themed value
    // (GamelistView.cpp:896-903).
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f) *
        esDeScrollFadeInAlpha(element, selected)

    // Real gradient-band rendering (DEcaffe's own leftband/rightband
    // elements: a thin vertical divider fading from `color` to
    // `colorEnd`) -- previously fell through to the plain path-image
    // branch below, which only ever applies ONE static tint color via
    // ColorFilter.tint, producing a solid flat-colored bar instead of a
    // real fade. `path` is deliberately ignored here: real ES-DE's own
    // technique tints a plain filler image with the gradient, which for
    // this renderer is simplest to reproduce as a plain gradient-filled
    // box at the same real pos/size -- visually equivalent, no image
    // decode needed.
    val gradientType = element.valueOrNull<EsDeThemeValue.Str>("gradientType")?.value
    val startColor = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    if (gradientType != null && startColor != null) {
        val endColor = element.valueOrNull<EsDeThemeValue.Color>("colorEnd")?.let { colorOf(it) } ?: startColor.copy(alpha = 0f)
        val brush = if (gradientType == "horizontal") {
            Brush.horizontalGradient(listOf(startColor, endColor))
        } else {
            Brush.verticalGradient(listOf(startColor, endColor))
        }
        // Real ES-DE semantics (ImageComponent setColorShift/-End): the
        // gradient is a color SHIFT ACROSS THE ELEMENT'S OWN IMAGE — a
        // per-vertex multiply of the gradient into the texture — not a
        // replacement for it. The previous approximation dropped the
        // image entirely and drew a flat gradient box, which erased
        // DEcaffe's decorative side-band art (background.png tinted by
        // the scheme's bgColor gradient): the bands rendered as plain
        // dark boxes instead of art. Compose equivalent: draw the image,
        // then modulate the gradient over the drawn content. Elements
        // with a gradient but NO existing image file keep the flat
        // gradient box (that is what they really are).
        val gradientImagePath = element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved
            ?.takeIf { File(it).exists() }
        val gradientModifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .graphicsLayer { alpha = opacity }
        if (gradientImagePath != null) {
            AsyncImage(
                model = gradientImagePath,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = gradientModifier.drawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.Modulate)
                },
            )
        } else {
            Box(modifier = gradientModifier.background(brush))
        }
        return
    }

    // Real gameselector-driven artwork: an element with `gameselectorEntry`
    // (DEcaffe's own game1..game9 mosaic tiles) has no static `path` of
    // its own at all -- its real image comes from whichever game
    // GameSelector picked for that slot, using that game's OWN already-
    // resolved artwork (LibraryEntry.artworkUri, the same real per-game
    // media EsDeArtwork.resolve found at scan time). This uses droidtop's
    // default miximage/cover/screenshot/... priority rather than THIS
    // element's own real `imageType` ordering (e.g. "screenshot,cover,
    // titlescreen") -- a real, honest simplification: re-deriving the
    // exact gamesRoot/system/romBaseName EsDeArtwork.resolve's imageTypes
    // overload needs from a LibraryEntry alone isn't reliably possible
    // for every provider today, so this reuses the artwork already
    // resolved once at scan time instead of re-resolving per element.
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt()
    val path = if (gameselectorEntry != null) {
        gameSelection.getOrNull(gameselectorEntry)?.artworkUri
    } else {
        // Real ImageComponent behavior: `path` applies only when its file
        // actually exists, else the element's own `default` image does --
        // real ES-DE's parser keeps a PATH property even when the file is
        // missing (ThemeData.cpp:2323-2377 only logs), so the existence
        // check must happen here at apply time. Art Book Next's system
        // logo is the real confirmed case: `<path>` templated on
        // ${system.theme} with a real `<default>` fallback logo for
        // systems it has no art for -- without this check the dead path
        // rendered nothing and the declared fallback never showed.
        element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved?.takeIf { File(it).exists() }
            ?: element.valueOrNull<EsDeThemeValue.Path>("default")?.resolved?.takeIf { File(it).exists() }
    } ?: return
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    // Real `brightness` (GuiComponent.cpp:393-394, clamped -2..2) and
    // `saturation` (GuiComponent.cpp:404-405, clamped 0..1), applied by
    // core.glsl:117-134 together with the `color` colorShift. Reuses the
    // ONE real implementation of that shader pipeline this package
    // already has -- esDeImageColorFilter, written for the
    // carousel/grid/textlist item images -- rather than a second copy of
    // the same maths. `dimming` is 1.0 here: it is real ES-DE's
    // unfocused-ITEM dimming, which only exists inside a primary list
    // widget, not for a standalone `image` element.
    val brightness = (element.valueOrNull<EsDeThemeValue.FloatValue>("brightness")?.value ?: 0f)
        .coerceIn(-2f, 2f)
    val saturation = (element.valueOrNull<EsDeThemeValue.FloatValue>("saturation")?.value ?: 1f)
        .coerceIn(0f, 1f)
    val imageColorFilter = esDeImageColorFilter(tint, saturation, brightness, dimming = 1f)
    val filterQuality = esDeFilterQuality(element) ?: FilterQuality.Low
    // Real properties (ImageComponent's own opacity/cornerRadius) --
    // opacity in particular matters a lot for real themes that fade
    // decorative art in/out.
    val cornerRadiusFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("cornerRadius")?.value ?: 0f
    // Real ImageComponent.cpp: cornerRadius scales against screen WIDTH,
    // not height -- confirmed against real ES-DE source (glm::clamp(...) *
    // mRenderer->getScreenWidth()), the one axis-exception also found for
    // help-bar entrySpacing (see EsDeThemedHelpSystem's own comment).
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    // Real properties, previously not applied at all -- see this file's
    // own EsDeThemedImage doc comment for the real, confirmed carousel
    // outline/fade misalignment this caused (one real source image meant
    // to be mirrored twice around the carousel, both instances rendering
    // in the same orientation without these). Rotation applies around the
    // element's own real `origin` point (same anchor `pos` already uses),
    // not Compose's center-of-box default.
    val originFraction = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val rotation = element.valueOrNull<EsDeThemeValue.FloatValue>("rotation")?.value ?: 0f
    val flipHorizontal = element.valueOrNull<EsDeThemeValue.Bool>("flipHorizontal")?.value ?: false
    val flipVertical = element.valueOrNull<EsDeThemeValue.Bool>("flipVertical")?.value ?: false
    // Real cropSize property (game1..game9's own mosaic tiles all declare
    // one): real ES-DE crops the source image to a specific sub-rectangle
    // before display. This renderer doesn't decode the source image's own
    // intrinsic size, so an exact sub-rectangle crop isn't implemented --
    // ContentScale.Crop (fill the given box, cropping equally from the
    // overflowing dimension) is an honest approximation, not a precise
    // match, same "no intrinsic-size decode" limitation sizeOf's own doc
    // comment already notes for maxSize.
    val hasCropSize = element.valueOrNull<EsDeThemeValue.Pair>("cropSize") != null
    val placement = Modifier
        .absoluteOffset(x = offsetX, y = offsetY)
        .size(width = width, height = height)
        .graphicsLayer {
            transformOrigin = TransformOrigin(originFraction.x, originFraction.y)
            rotationZ = rotation
            scaleX = if (flipHorizontal) -1f else 1f
            scaleY = if (flipVertical) -1f else 1f
        }
        .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it }

    // Real `tile` -- repeats the source texture across the element's box
    // instead of stretching one copy over it. Falls through to the plain
    // stretched draw below when the source can't be decoded to a bitmap
    // (an SVG tile) -- see rememberTileBitmap's own doc comment.
    val tileEnabled = element.valueOrNull<EsDeThemeValue.Bool>("tile")?.value == true
    val tileBitmap = rememberTileBitmap(if (tileEnabled) path else null)
    if (tileBitmap != null) {
        val tileSize = element.valueOrNull<EsDeThemeValue.Pair>("tileSize")
        val density = LocalDensity.current
        val tile = esDeTileSize(
            declaredWidthPx = if (tileSize == null) null else with(density) { (viewWidth * tileSize.x.coerceIn(0f, 1f)).toPx() },
            declaredHeightPx = if (tileSize == null) null else with(density) { (viewHeight * tileSize.y.coerceIn(0f, 1f)).toPx() },
            sourceWidthPx = tileBitmap.width.toFloat(),
            sourceHeightPx = tileBitmap.height.toFloat(),
        )
        if (tile != null) {
            // Honest gap, narrow and deliberate: this Compose version's
            // ImageShader takes no filterQuality, and a per-tile
            // drawImage loop (which does) would be tens of thousands of
            // draws for a small tile over a full-screen box. So a TILED
            // image keeps the platform's own filtering regardless of its
            // `interpolation` -- every other image path honors it.
            val brush = remember(tileBitmap) {
                ShaderBrush(ImageShader(tileBitmap, TileMode.Repeated, TileMode.Repeated))
            }
            val scaleX = tile.first / tileBitmap.width
            val scaleY = tile.second / tileBitmap.height
            Box(
                modifier = placement.drawBehind {
                    // The shader repeats at the bitmap's own pixel size, so
                    // the whole tiled fill is drawn scaled by the requested
                    // tile size over the un-scaled box -- equivalent to
                    // repeating at the tile size itself.
                    scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset.Zero) {
                        drawRect(
                            brush = brush,
                            size = Size(size.width / scaleX, size.height / scaleY),
                            alpha = opacity,
                            colorFilter = imageColorFilter,
                        )
                    }
                },
            )
            return
        }
    }

    AsyncImage(
        model = path,
        contentDescription = null,
        // Real ES-DE `<color>` on an image is a colorSHIFT -- a real
        // multiply of the color into the texture's own existing pixels
        // (ImageComponent::setColorShift, real default 0xFFFFFFFF = a
        // true no-op multiply), not a full replace. Compose's default
        // ColorFilter.tint blend mode is SrcIn, which REPLACES every
        // opaque source pixel with a flat, uniform color -- any real,
        // detailed background/decorative art tinted this way rendered as
        // a flat, featureless silhouette instead of its own real
        // shape/gradient/texture tinted through, a real, confirmed-live
        // bug found by diffing an on-device screenshot against the
        // theme's own bundled reference render. The colorShift is now the
        // multiply ROW of esDeColorMatrix instead of a separate
        // BlendMode.Modulate tint, so it composes with the element's own
        // real brightness/saturation in one filter.
        colorFilter = imageColorFilter,
        alpha = opacity,
        filterQuality = filterQuality,
        contentScale = if (hasCropSize) ContentScale.Crop else ContentScale.Fit,
        modifier = placement,
    )
}

/**
 * Decodes a theme's own local image file to a bitmap so the real
 * `tile`/`tileSize` rules can be applied -- they all need the source's
 * real intrinsic pixel size (ImageComponent.cpp:838-850), which Coil's
 * AsyncImage never hands back. Theme assets are always real files on
 * disk, so BitmapFactory reads them directly, off the main thread.
 *
 * Returns null for [path] null (the element isn't tiled) and for any file
 * BitmapFactory can't decode -- notably SVG, which is the honest gap
 * here: an SVG tile falls back to the plain stretched draw rather than
 * being rendered as something invented.
 */
@Composable
private fun rememberTileBitmap(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}

/**
 * Real ES-DE tile sizing, transcribed from ImageComponent.cpp:653-673
 * (the theme parse) and ImageComponent.cpp:838-850 (the fallbacks):
 *
 *  - `tileSize` is a fraction of the PARENT's size
 *    (ImageComponent.cpp:525-527 -- for a top-level themed element that
 *    parent is the view itself), which is why this takes it already
 *    resolved to pixels.
 *  - A `tileSize` of exactly 0 0 is rejected outright and turns tiling
 *    off (ImageComponent.cpp:657-663), which is what null means here.
 *  - No `tileSize` at all means the texture's own intrinsic size
 *    (ImageComponent.cpp:838-842).
 *  - Exactly one zero component is derived from the other via the
 *    source's real aspect ratio (ImageComponent.cpp:845-850).
 *
 * `tileHorizontalAlignment`/`tileVerticalAlignment`
 * (ImageComponent.cpp:686-716) are deliberately not implemented: neither
 * appears in any of the ten real themes measured for this pass, and the
 * anchor they choose only shows when the tile grid doesn't divide the box
 * evenly.
 */
internal fun esDeTileSize(
    declaredWidthPx: Float?,
    declaredHeightPx: Float?,
    sourceWidthPx: Float,
    sourceHeightPx: Float,
): kotlin.Pair<Float, Float>? {
    if (sourceWidthPx <= 0f || sourceHeightPx <= 0f) return null
    if (declaredWidthPx == 0f && declaredHeightPx == 0f) return null
    var width = declaredWidthPx ?: 0f
    var height = declaredHeightPx ?: 0f
    val ratio = sourceWidthPx / sourceHeightPx
    if (width == 0f && height == 0f) {
        width = sourceWidthPx
        height = sourceHeightPx
    } else if (width == 0f) {
        width = kotlin.math.round(height * ratio)
    } else if (height == 0f) {
        height = kotlin.math.round(width / ratio)
    }
    if (width <= 0f || height <= 0f) return null
    return width to height
}

/**
 * Real ES-DE `TextComponent` behavior, ported properly -- the previous
 * version only read `text`/`color`/`pos`, ignoring every other property
 * the parser already extracts correctly (`fontSize`, `horizontalAlignment`,
 * `verticalAlignment`, `letterCase`, `backgroundColor`, `opacity`,
 * `lineSpacing`). That gap mattered far more than it looks: `text`
 * elements are how a theme renders every plain label (headers, info-panel
 * fields, ...), and rendering them at Compose's default ~14sp regardless
 * of the theme's own real `fontSize` (a fraction of screen height, same
 * convention already ported for textlist rows) made a lot of real theme
 * content either invisibly small or wildly mis-scaled relative to the
 * rest of the screen -- a real, likely explanation for content that
 * "looked wrong" without an obvious crash or missing element.
 *
 * `size`, when the theme declares one, becomes both a real wrap width
 * (`fillMaxWidth` + `TextAlign`) and a real box for `verticalAlignment`
 * to position within (`Box`'s own `contentAlignment`) -- matching real
 * ES-DE's own "mSize.y acts as a bounding box the text centers/aligns
 * within" behavior (`TextComponent::onTextUpdated`). An element with no
 * `size` at all (common for a single short label) keeps the old
 * point-anchored `pos`-only placement, since there's no real box to
 * align within.
 */
@Composable
private fun EsDeThemedText(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    gameSelection: List<LibraryEntry>,
    systemContext: EsDeSystemContext? = null,
) {
    // Real `metadataElement` -- see esDeHiddenByMetadataFlag's own doc
    // comment. `text` is where real themes flag it most (five of the ten
    // measured for this pass), since a hidden-metadata game is exactly the
    // case where a theme wants its per-game labels to disappear.
    if (esDeHiddenByMetadataFlag(element, gameSelection.getOrNull(0))) return
    // Real gameselector-bound title text (DEcaffe's own `text name="game"`,
    // `metadata=name`): previously fell through the plain `?: return`
    // below every single time, since a metadata-bound element has no
    // static `text` property of its own at all -- silently rendering
    // nothing despite being a real, positioned "text" element, not one of
    // the deferred badges/rating/gamelistinfo types. Real ES-DE convention
    // when `gameselectorEntry` is omitted on a metadata-bound element:
    // implicitly entry 0 of whichever gameselector is in scope (matches
    // DEcaffe's own pairing of this element with `screen2`'s explicit
    // `gameselectorEntry=0` poster -- same featured game, paired caption).
    // Real, extended beyond just "name" now that LibraryEntry actually
    // models the rest of real ES-DE's own MetaData fields (description/
    // developer/publisher/genre/players -- see that field's own doc
    // comment): every metadata-bound `text` element real ES-DE themes use
    // for these keys now resolves to real scraped data instead of always
    // falling through to `defaultValue`.
    val metadata = element.valueOrNull<EsDeThemeValue.Str>("metadata")?.value
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt() ?: 0
    val selectedGame = gameSelection.getOrNull(gameselectorEntry)
    // Real, confirmed-live bug this fixes: `metadata="description"` is the
    // real THEME key (confirmed against GamelistView::getMetadataValue,
    // GamelistView.cpp:1007-1008 -- `if (metadata == "description") return
    // file->metadata.get("desc")`, i.e. the theme key "description" maps
    // to the FILE's own internal "desc" field, they are NOT the same
    // string). droidtop matched on "desc" instead of "description" --
    // decaffe's own theme.xml declares `<metadata>description</metadata>`
    // (its own real per-game description panel), which never matched
    // either branch, silently falling through to defaultValue every
    // single time regardless of real scraped data. Real per-game text
    // metadata keys ES-DE themes actually use, transcribed from the same
    // function (GamelistView.cpp:1005-1040): description/developer/
    // publisher/genre/players/favorite/completed/kidgame/broken/manual/
    // playtime/altemulator.
    val metadataText = when (metadata) {
        "name" -> selectedGame?.title
        "description" -> selectedGame?.description
        "developer" -> selectedGame?.developer
        "publisher" -> selectedGame?.publisher
        "genre" -> selectedGame?.genre
        "players" -> selectedGame?.players
        "favorite" -> selectedGame?.favorite?.let { if (it) "yes" else "no" }
        "completed" -> selectedGame?.completed?.let { if (it) "yes" else "no" }
        "kidgame" -> selectedGame?.kidGame?.let { if (it) "yes" else "no" }
        "broken" -> selectedGame?.broken?.let { if (it) "yes" else "no" }
        "manual" -> selectedGame?.let { if (it.manualUri != null) "yes" else "no" }
        "altemulator" -> selectedGame?.altEmulator
        // Real ES-DE format (File::getPlayTimeString): "Xh Ym", or "Never
        // played" for zero -- same real convention droidtop's own
        // EntryDetailScreen already uses ("Played N min"), transcribed
        // here to match the theme-bound case too.
        "playtime" -> selectedGame?.playtimeSeconds?.let { seconds ->
            if (seconds <= 0) "Never played" else "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
        else -> null
    }
    // Real `systemdata` binding, transcribed from SystemView.cpp:913-947
    // (name/fullname) and :966-1030 (the gamecount family, exact real
    // format strings including singular forms and the favorites/recent
    // collections' bare-count special case). An unrecognized systemdata
    // value renders as its own literal string -- that IS real ES-DE's own
    // behavior (SystemView.cpp:926/:1028 both setValue the raw string),
    // not a droidtop fallback invention. decaffe's own bottom info strip
    // ("10 GAMES (2 FAVORITES)" in its reference render) is
    // systemdata=gamecount -- unrendered entirely before this.
    val systemdata = element.valueOrNull<EsDeThemeValue.Str>("systemdata")?.value
    fun plural(count: Int, singular: String, pluralForm: String) = if (count == 1) singular else pluralForm
    val systemdataText: String? = if (systemdata == null || systemContext == null) {
        null
    } else {
        val games = systemContext.gameCount
        val favorites = systemContext.favoriteCount
        val gamesText = "$games " + plural(games, "game", "games")
        when (systemdata) {
            "name", "fullname" -> systemContext.name
            "gamecount" ->
                if (systemContext.countsOnly) gamesText
                else "$gamesText ($favorites " + plural(favorites, "favorite", "favorites") + ")"
            "gamecountGames" -> gamesText
            "gamecountGamesNoText" -> games.toString()
            "gamecountFavorites" ->
                if (systemContext.countsOnly) "" else "$favorites " + plural(favorites, "favorite", "favorites")
            "gamecountFavoritesNoText" ->
                if (systemContext.countsOnly) "" else favorites.toString()
            else -> systemdata
        }
    }
    val resolvedText = if (systemdataText != null) {
        systemdataText
    } else if (metadata != null) {
        metadataText ?: element.valueOrNull<EsDeThemeValue.Str>("defaultValue")?.value
    } else {
        element.valueOrNull<EsDeThemeValue.Str>("text")?.value
    } ?: return
    // Real ES-DE convention: ":space:" renders as blank (reserves the
    // element's own position/size, shows no visible text) rather than the
    // literal string. Case-insensitive -- decaffe's own theme.xml writes
    // it as ":SPACE:" in at least one real variant block, which rendered
    // the literal token on-device under an exact-case check.
    val rawText = if (resolvedText.equals(":space:", ignoreCase = true)) "" else resolvedText
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val text = if (uppercase) rawText.uppercase() else rawText

    val hasSize = element.valueOrNull<EsDeThemeValue.Pair>("size") != null
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)

    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val backgroundColor = element.valueOrNull<EsDeThemeValue.Color>("backgroundColor")?.let { colorOf(it) }
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)

    // Real ES-DE default text size convention (same as textlist rows):
    // fontSize is a fraction of screen height.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeDp = (fontSizeFraction * viewHeight.value).dp
    val fontSizeSp = with(LocalDensity.current) { fontSizeDp.toSp() }
    val lineSpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("lineSpacing")?.value ?: 1.5f).coerceIn(0.5f, 3f)

    val textAlign = when (element.valueOrNull<EsDeThemeValue.Str>("horizontalAlignment")?.value) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.End
        else -> TextAlign.Start
    }
    val boxAlignment = when (element.valueOrNull<EsDeThemeValue.Str>("verticalAlignment")?.value) {
        "center" -> Alignment.CenterStart
        "bottom" -> Alignment.BottomStart
        else -> Alignment.TopStart
    }

    if (hasSize) {
        Box(
            modifier = Modifier
                .absoluteOffset(x = offsetX, y = offsetY)
                .size(width = width, height = height)
                .let { if (backgroundColor != null) it.background(backgroundColor.copy(alpha = backgroundColor.alpha * opacity)) else it },
            contentAlignment = boxAlignment,
        ) {
            Text(
                text = text,
                color = color.copy(alpha = color.alpha * opacity),
                fontSize = fontSizeSp,
                fontFamily = themeFontFamily(element),
                lineHeight = fontSizeSp * lineSpacing,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        // Real bug this fixes: with no declared `size`, origin math was
        // applied against a width/height of ZERO -- decaffe anchors
        // nearly every label with `origin="0.5 0.5"` (center-anchored),
        // so `pos - 0 * origin` always reduced to plain top-left
        // placement at `pos`, silently ignoring origin entirely instead
        // of centering the element's own REAL rendered size on `pos` the
        // way real ES-DE's `TextComponent` does (it measures its own
        // text first, then applies origin against that real size --
        // confirmed against `GuiComponent::getPosition`'s real origin
        // formula, the same one every real ES-DE component uses). This
        // is why sidebar labels like decaffe's own "SYSTEM TITLE:"/
        // "RELEASED:" rendered visibly offset from the reference render.
        // EsDeAutoOriginBox measures Text FIRST (loose max-width, real
        // rendered size), THEN positions using that real size -- the
        // only way to get this right in Compose's own layout model,
        // since the size genuinely isn't known before the child itself
        // is measured.
        // Real ES-DE draws `backgroundColor` sized to the element's own
        // real `mSize` PLUS `backgroundMargins` on both sides
        // (TextComponent.cpp:263-274: `drawRect(0, 0, mSize.x +
        // margins.x + margins.y, ...)`) -- for an auto-sized element,
        // `mSize` IS the measured text size (real ES-DE always measures
        // it, whether from a declared `size` or from the rendered text),
        // so background isn't a has-size-only feature. Applying it here
        // as padding+background on the SAME child EsDeAutoOriginBox
        // measures means the background box's real total size (text +
        // margins) is exactly what origin math ends up centering/
        // anchoring, matching real ES-DE with no separate pre-measure
        // pass needed.
        val backgroundMarginsFraction = element.valueOrNull<EsDeThemeValue.Pair>("backgroundMargins")
        val backgroundMarginX = backgroundMarginsFraction?.let { (viewWidth * it.x) } ?: 0.dp
        val backgroundMarginY = backgroundMarginsFraction?.let { (viewHeight * it.y) } ?: 0.dp
        EsDeAutoOriginBox(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            posFraction = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f),
            originFraction = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f),
        ) {
            Box(
                modifier = Modifier
                    .let { if (backgroundColor != null) it.background(backgroundColor.copy(alpha = backgroundColor.alpha * opacity)) else it }
                    .padding(horizontal = backgroundMarginX, vertical = backgroundMarginY),
            ) {
                Text(
                    text = text,
                    color = color.copy(alpha = color.alpha * opacity),
                    fontSize = fontSizeSp,
                    fontFamily = themeFontFamily(element),
                    lineHeight = fontSizeSp * lineSpacing,
                    textAlign = textAlign,
                )
            }
        }
    }
}

/**
 * Real origin-correction for an auto-sized element -- see
 * [EsDeThemedText]'s own doc comment for the real, confirmed bug this
 * fixes. Measures [content] against loose constraints (bounded only by
 * the themed area itself, so a long string still wraps rather than
 * overflowing unbounded), THEN computes `pos*view - measuredSize*origin`
 * using that REAL measured size, then places it there -- the standard
 * Compose pattern (a custom [Layout]) for "position depends on this
 * child's own rendered size," which a pre-computed `absoluteOffset`
 * modifier genuinely cannot express since it runs before the child is
 * ever measured.
 */
@Composable
private fun EsDeAutoOriginBox(
    viewWidth: Dp,
    viewHeight: Dp,
    posFraction: EsDeThemeValue.Pair,
    originFraction: EsDeThemeValue.Pair,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, _ ->
        val maxWidthPx = viewWidth.roundToPx()
        val maxHeightPx = viewHeight.roundToPx()
        val childConstraints = androidx.compose.ui.unit.Constraints(maxWidth = maxWidthPx, maxHeight = maxHeightPx)
        val placeable = measurables.firstOrNull()?.measure(childConstraints)
        val w = placeable?.width ?: 0
        val h = placeable?.height ?: 0
        val posXPx = (viewWidth.toPx() * posFraction.x).toInt()
        val posYPx = (viewHeight.toPx() * posFraction.y).toInt()
        val x = posXPx - (w * originFraction.x).toInt()
        val y = posYPx - (h * originFraction.y).toInt()
        layout(maxWidthPx, maxHeightPx) {
            placeable?.place(x, y)
        }
    }
}

/**
 * Real `video` element playback -- a real ExoPlayer/media3 instance
 * (`androidx.media3:media3-exoplayer`, `:media3-ui`), not a static poster,
 * when the selected game has a real, scraped [LibraryEntry.videoUri] (see
 * [dev.droidtop.library.EsDeArtwork.resolveVideo]'s own doc comment for
 * the real ES-DE `videos` media-type convention this reads). Loops
 * (`repeatMode = Player.REPEAT_MODE_ONE`, matching real ES-DE's own
 * gamelist-preview behavior of looping a short clip while a game stays
 * selected) and plays MUTED -- real ES-DE's own default is audible
 * preview audio, but droidtop has no per-view "is this screen actually
 * focused/visible" signal this composable can see (unlike real ES-DE's
 * own view-lifecycle hook that stops playback on navigating away), so
 * muted is the honest, safe default rather than risking audio from an
 * off-screen or backgrounded preview; a future volume/mute setting is a
 * real, separate, smaller follow-up once that signal exists. Falls back
 * to [EsDeThemedFallbackImage]'s exact same static-poster path (its own
 * `default`/`defaultImage`/`path`/gameselector-artwork chain) when no
 * video is present -- e.g. this specific game was never scraped for
 * video, or this is a gameselector mosaic-tile slot with no video concept
 * at all.
 */
@Composable
private fun EsDeThemedVideo(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt()
    val selected = gameSelection.getOrNull(gameselectorEntry ?: 0)
    // Real `metadataElement` -- see esDeHiddenByMetadataFlag's own doc comment.
    if (esDeHiddenByMetadataFlag(element, selected)) return
    val videoUri = selected?.videoUri
    if (videoUri == null) {
        EsDeThemedFallbackImage(element, viewWidth, viewHeight, gameSelection)
        return
    }

    // Real `delay` (VideoComponent.cpp:325-330): seconds, clamped to
    // 0..15, before playback starts -- and real ES-DE sets
    // `showStaticImageDelay` when it is non-zero (VideoComponent.cpp:329-
    // 330), i.e. the element's own static image is what fills the delay,
    // which is exactly the fallback this renderer already draws. Keyed on
    // the video itself so moving the cursor to another game restarts the
    // delay rather than skipping it.
    val delaySeconds = (element.valueOrNull<EsDeThemeValue.FloatValue>("delay")?.value ?: 0f)
        .coerceIn(0f, 15f)
    var delayElapsed by remember(videoUri) { mutableStateOf(delaySeconds == 0f) }
    LaunchedEffect(videoUri, delaySeconds) {
        if (delaySeconds > 0f) {
            delay((delaySeconds * 1000f).toLong())
            delayElapsed = true
        }
    }
    if (!delayElapsed) {
        EsDeThemedFallbackImage(element, viewWidth, viewHeight, gameSelection)
        return
    }

    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    // Real `videoCornerRadius` (VideoComponent.cpp:291-293) -- the PLAYING
    // surface's own radius. `imageCornerRadius` (VideoComponent.cpp:287-
    // 289) is a separate real property belonging to this element's static
    // image, which is why it stays where it is, in
    // EsDeThemedFallbackImage. droidtop previously read imageCornerRadius
    // here, so a theme that rounded only its static poster also rounded
    // its playing video and one that rounded only the video rounded
    // neither.
    val cornerRadiusFraction = (element.valueOrNull<EsDeThemeValue.FloatValue>("videoCornerRadius")?.value ?: 0f)
        .coerceIn(0f, 0.5f)
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f) *
        esDeScrollFadeInAlpha(element, selected)

    val context = LocalContext.current
    var videoSize by remember(videoUri) { mutableStateOf(VideoSize.UNKNOWN) }
    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        // The decoded frame's real dimensions are what real ES-DE's own
        // fit and pillarbox rules are computed from
        // (VideoFFmpegComponent.cpp:122) -- ExoPlayer only knows them once
        // the track is read, so the geometry below recomposes when it does.
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Real `pillarboxes` (VideoComponent.cpp:419-420, default TRUE at
    // VideoComponent.cpp:51) and `pillarboxThreshold`
    // (VideoComponent.cpp:430-434, real defaults 0.85/0.90 at
    // VideoComponent.cpp:39). See EsDeVideoLayout.kt for the geometry and
    // for the RESIZE_MODE_ZOOM cropping bug this replaces.
    val drawPillarboxes = element.valueOrNull<EsDeThemeValue.Bool>("pillarboxes")?.value ?: true
    val threshold = element.valueOrNull<EsDeThemeValue.Pair>("pillarboxThreshold")
    // Real ES-DE stretches only for a two-component `size`; `maxSize`
    // fits (VideoFFmpegComponent.cpp:127-142 vs :173-176). Every one of
    // the ten real themes measured for this pass uses `maxSize`.
    val declaredSize = element.valueOrNull<EsDeThemeValue.Pair>("size")
    val stretch = declaredSize != null && declaredSize.x > 0f && declaredSize.y > 0f
    val frame = esDeVideoFrame(
        areaWidth = width.value,
        areaHeight = height.value,
        sourceWidth = videoSize.width,
        sourceHeight = videoSize.height,
        stretch = stretch,
        drawPillarboxes = drawPillarboxes,
        thresholdX = threshold?.x ?: 0.85f,
        thresholdY = threshold?.y ?: 0.90f,
        cornerRadius = cornerRadius.value,
    )

    Box(
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .graphicsLayer { alpha = opacity },
        contentAlignment = Alignment.Center,
    ) {
        // The black frame real ES-DE renders BEHIND every video
        // (VideoFFmpegComponent.cpp:220-223) -- it is what the
        // pillarboxes/letterboxes actually are, and it also covers the
        // moment before the first frame decodes.
        Box(
            modifier = Modifier
                .size(width = frame.frameWidth.dp, height = frame.frameHeight.dp)
                .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it }
                .background(Color.Black),
        )
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                    // Real ES-DE CONTAINS the video in its themed area
                    // (VideoFFmpegComponent.cpp:127-142) or stretches it
                    // (:173-176) -- it never crops, which is what the
                    // previous RESIZE_MODE_ZOOM did.
                    resizeMode = if (stretch) {
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .let {
                    if (cornerRadius > 0.dp && frame.roundVideoCorners) {
                        it.clip(RoundedCornerShape(cornerRadius))
                    } else {
                        it
                    }
                },
        )
    }
}

/**
 * Which decode engine an `animation` element's `path` really selects --
 * transcribed from real ES-DE's own dispatch (SystemView.cpp:648-676 and
 * the identical GamelistView.cpp block): strictly by file extension,
 * ".json" -> LottieAnimComponent, ".gif" -> GIFAnimComponent, anything
 * else logs a warning and creates NO component at all ("Only .gif and
 * .json extensions are supported", THEMES.md's own `path` property doc).
 * droidtop's one deliberate extension beyond that real set: ".png"/
 * ".apng" -> APNG playback (docs/SPEC.md names "animation (GIF/APNG)" as
 * the goal), which real ES-DE would refuse -- APNG4Android decodes both
 * formats with one API, so supporting it costs nothing and loses no
 * real-theme compatibility (a real theme written for ES-DE can't be
 * using .apng paths anyway, so this only ever widens what renders).
 * Pure and JVM-testable -- no Android/filesystem dependency.
 */
internal enum class EsDeAnimationKind { GIF, APNG, LOTTIE, UNSUPPORTED }

internal fun esDeAnimationKind(path: String): EsDeAnimationKind {
    // Extension of the FILENAME, not of the whole path -- a dot in a
    // parent directory name must not read as an extension.
    val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "gif" -> EsDeAnimationKind.GIF
        "png", "apng" -> EsDeAnimationKind.APNG
        "json" -> EsDeAnimationKind.LOTTIE
        else -> EsDeAnimationKind.UNSUPPORTED
    }
}

/**
 * Real `animation` element playback -- actual GIF/APNG frame animation
 * (APNG4Android's [GifDrawable]/[APNGDrawable], one shared
 * FrameAnimationDrawable API for both formats), replacing the static
 * first-frame-image fallback this element type rendered through before.
 * Dispatch is real ES-DE's own extension-based one (see
 * [esDeAnimationKind]); like real ES-DE, a component only exists when
 * `path` is declared AND the file exists (SystemView.cpp:646 gates on
 * `has("path")`; there is no `default` fallback property in the real
 * animation schema, unlike image/video -- confirmed against
 * ThemeData.cpp:415-438's property list).
 *
 * Lottie (".json") is parsed but deliberately NOT rendered: real ES-DE
 * treats it as a genuinely separate component class
 * (LottieAnimComponent, an rlottie integration) -- logged once per path
 * so a theme author sees why, not silently dropped.
 *
 * Real properties applied: pos/size/maxSize/origin/rotation/opacity via
 * the same [sizeOf]/[positionOf]/graphicsLayer helpers every other
 * element uses; `color` as a real multiply color shift
 * (PorterDuff.Mode.MULTIPLY -- the AndroidView equivalent of the
 * BlendMode.Modulate rationale documented in [EsDeThemedImage]);
 * `cornerRadius` against screen WIDTH (GIFAnimComponent.cpp:399-401,
 * same axis exception as image); `iterationCount` via
 * FrameAnimationDrawable.setLoopLimit (real range 0-10, 0 = infinite,
 * GIFAnimComponent.cpp:370-371 / THEMES.md). Real, honest gaps -- all
 * parsed, none rendered, because APNG4Android's decoder has no
 * corresponding control: `speed` (real clamp 0.2-3.0), `direction`
 * (normal/reverse/alternate/alternateReverse, including alternate's real
 * iteration-doubling, GIFAnimComponent.cpp:343-373), `interpolation`,
 * `colorEnd`/`gradientType` (a per-vertex gradient shift a flat
 * ColorFilter can't express), `brightness`/`saturation` (not applied for
 * plain images either), and `stationary`/`metadataElement` (droidtop has
 * no slide-transition or fast-scroll-fade machinery for any element yet).
 */
@Composable
private fun EsDeThemedAnimation(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    // No gameSelection parameter, unlike image/video: the real animation
    // schema has no gameselectorEntry property at all (ThemeData.cpp:
    // 415-438) -- an animation is always its own declared file.
    val path = element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved
    if (path == null || !File(path).exists()) return
    val kind = esDeAnimationKind(path)
    if (kind == EsDeAnimationKind.LOTTIE || kind == EsDeAnimationKind.UNSUPPORTED) {
        // Same real outcome as ES-DE's own "Invalid theme configuration"
        // warning path (SystemView.cpp:667-675): no component.
        android.util.Log.w(
            "droidtop.EsDeTheme",
            "animation element ${element.key}: ${if (kind == EsDeAnimationKind.LOTTIE) "Lottie (.json) animations are not implemented" else "unsupported animation extension"} ($path)",
        )
        return
    }
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val originFraction = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val rotation = element.valueOrNull<EsDeThemeValue.FloatValue>("rotation")?.value ?: 0f
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    // Real GIFAnimComponent.cpp:399-401: cornerRadius scales against
    // screen WIDTH (the same one-axis exception image documents).
    val cornerRadiusFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("cornerRadius")?.value ?: 0f
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    // Real clamp 0-10, 0 = infinite (GIFAnimComponent.cpp:370-371).
    // setLoopLimit's own 0 also means unlimited, a direct match.
    val iterationCount = (element.valueOrNull<EsDeThemeValue.UInt>("iterationCount")?.value?.toInt() ?: 0).coerceIn(0, 10)
    // Real `size` vs `maxSize` semantics, same as sizeOf's own doc
    // comment: an exact `size` stretches, `maxSize` fits within bounds
    // preserving aspect -- ImageView scale types express exactly that.
    val hasExactSize = element.valueOrNull<EsDeThemeValue.Pair>("size") != null
    val drawable = remember(path, iterationCount) {
        val loader = FileLoader(path)
        val animation: FrameAnimationDrawable<*> = when (kind) {
            EsDeAnimationKind.GIF -> GifDrawable(loader)
            else -> APNGDrawable(loader)
        }
        animation.setLoopLimit(iterationCount)
        animation
    }
    // FrameAnimationDrawable runs its own decode thread -- stop it when
    // this element leaves composition (same lifecycle discipline as
    // EsDeThemedVideo's player.release()).
    DisposableEffect(drawable) {
        onDispose { drawable.stop() }
    }
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = if (hasExactSize) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
                if (tint != null) {
                    // Real ES-DE `color` on an animation is the same
                    // multiply color SHIFT as on images
                    // (GIFAnimComponent's mColorShift, THEMES.md:
                    // "multiplying each pixel's color by this color
                    // value") -- MULTIPLY, never SRC_IN.
                    colorFilter = PorterDuffColorFilter(
                        android.graphics.Color.argb(
                            (tint.alpha * 255).toInt(),
                            (tint.red * 255).toInt(),
                            (tint.green * 255).toInt(),
                            (tint.blue * 255).toInt(),
                        ),
                        PorterDuff.Mode.MULTIPLY,
                    )
                }
                setImageDrawable(drawable)
            }
        },
        update = { view -> if (view.drawable !== drawable) view.setImageDrawable(drawable) },
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .graphicsLayer {
                alpha = opacity
                transformOrigin = TransformOrigin(originFraction.x, originFraction.y)
                rotationZ = rotation
            }
            .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it },
    )
}

/**
 * Real fallback for `video` elements: their own `default`/
 * `defaultImage`/`path` PATH property when present, shown as a plain
 * static image -- or, for a gameselector-driven element (DEcaffe's own
 * `screen2`, the large game-preview poster, which has NO static path
 * property of its own at all), the selected game's own already-resolved
 * artwork, same real per-game-image approach as [EsDeThemedImage]'s own
 * gameselectorEntry handling (see that function's doc comment for the
 * same "default priority order, not this element's own imageType"
 * simplification). [EsDeThemedVideo]'s own fallback when a `video`
 * element's selected game has no scraped video; `animation` elements now
 * play for real instead -- see [EsDeThemedAnimation].
 */
@Composable
private fun EsDeThemedFallbackImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt()
    val path = if (gameselectorEntry != null) {
        gameSelection.getOrNull(gameselectorEntry)?.artworkUri
    } else {
        // Same real apply-time existence checks as EsDeThemedImage's own
        // path/default chain (see that comment) -- a dead templated path
        // must fall through, not shadow the next candidate.
        element.valueOrNull<EsDeThemeValue.Path>("default")?.resolved?.takeIf { File(it).exists() }
            ?: element.valueOrNull<EsDeThemeValue.Path>("defaultImage")?.resolved?.takeIf { File(it).exists() }
            ?: element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved?.takeIf { File(it).exists() }
    } ?: return
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // "video"'s real property for its STATIC image is imageCornerRadius
    // (videoCornerRadius is the playing surface's own, separate real
    // property) -- and a static image is exactly what this fallback
    // draws. Only video elements reach here now that animation plays for
    // real (see EsDeThemedAnimation).
    val cornerRadiusFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("imageCornerRadius")?.value ?: 0f
    // Real ImageComponent.cpp: cornerRadius scales against screen WIDTH,
    // not height -- confirmed against real ES-DE source (glm::clamp(...) *
    // mRenderer->getScreenWidth()), the one axis-exception also found for
    // help-bar entrySpacing (see EsDeThemedHelpSystem's own comment).
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    // Same real cropSize approximation as EsDeThemedImage -- see that
    // function's own doc comment.
    val hasCropSize = element.valueOrNull<EsDeThemeValue.Pair>("cropSize") != null
    // Real `interpolation` -- on a `video` element real ES-DE applies the
    // same value to BOTH the video texture and this static image
    // (VideoComponent.cpp:270-284 calls mStaticImage.setLinearInterpolation
    // alongside its own flag). The static image half is the half droidtop
    // can honour; ExoPlayer's SurfaceView exposes no texture-filter knob,
    // so the playing surface keeps the platform default -- an honest gap,
    // not a silent one.
    val filterQuality = esDeFilterQuality(element) ?: FilterQuality.Low
    AsyncImage(
        model = path,
        contentDescription = null,
        filterQuality = filterQuality,
        // Real ES-DE `<color>` on an image is a colorSHIFT -- a real
        // multiply of the color into the texture's own existing pixels
        // (ImageComponent::setColorShift, real default 0xFFFFFFFF = a
        // true no-op multiply), not a full replace. Compose's default
        // ColorFilter.tint blend mode is SrcIn, which REPLACES every
        // opaque source pixel with a flat, uniform color -- any real,
        // detailed background/decorative art tinted this way rendered as
        // a flat, featureless silhouette instead of its own real
        // shape/gradient/texture tinted through, a real, confirmed-live
        // bug found by diffing an on-device screenshot against the
        // theme's own bundled reference render. BlendMode.Modulate is
        // Compose's real multiply-blend equivalent.
        colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) },
        alpha = opacity,
        contentScale = if (hasCropSize) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it },
    )
}

/**
 * Real, live-updating `clock` rendering -- current wall-clock time,
 * formatted via the element's own real `format` property when present
 * (ES-DE's own strftime-style format string; falls back to a plain
 * default). Ticks every second via a real Compose LaunchedEffect/delay
 * loop, not a one-shot render. See [EsDeThemedDateTime] for the
 * genuinely different, metadata-bound (not live) `datetime` element.
 */
@Composable
private fun EsDeThemedClock(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "%H:%M"
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val formatted = remember(now, format) {
        runCatching {
            SimpleDateFormat(strftimeToJavaPattern(format), Locale.getDefault()).format(now)
        }.getOrDefault("")
    }
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Same real scaling axis as EsDeThemedText/textlist rows, but a
    // different real default: DateTimeComponent.cpp's own real default is
    // FONT_SIZE_SMALL (0.035), not TextComponent's FONT_SIZE_MEDIUM
    // (0.045) -- droidtop previously borrowed the wrong component's
    // default for this element type, confirmed against real ES-DE source.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    // Real clock background box (DateTimeComponent.cpp:163-175, guarded on
    // `backgroundColor` exactly as ES-DE guards on mClockBgColor) -- see
    // EsDeBackgroundBox. Five of the ten themes measured for this pass set
    // its padding, and a clock with a declared backgroundColor previously
    // drew no box at all, so the time floated over whatever art was behind
    // it.
    val background = backgroundBoxOf(element, viewWidth, viewHeight, opacity)
    Text(
        text = formatted,
        color = color.copy(alpha = color.alpha * opacity),
        fontSize = fontSizeSp,
        fontFamily = themeFontFamily(element),
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .esDeBackgroundBox(background),
    )
}

/**
 * Real, metadata-bound `datetime` rendering -- genuinely different from
 * [EsDeThemedClock]: reads one static real per-game date value (currently
 * only `releasedate`, real ES-DE's own "YYYYMMDDT000000" MD_DATE
 * convention -- see [LibraryEntry.releaseDate]'s own doc comment) and
 * formats it via the element's own real strftime-style `format` property
 * ONCE, not a live-ticking clock. Other real ES-DE datetime metadata keys
 * (`lastplayed`) aren't modeled on [LibraryEntry] yet -- a real, honest
 * gap, not silently pretended away.
 */
@Composable
private fun EsDeThemedDateTime(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val metadata = element.valueOrNull<EsDeThemeValue.Str>("metadata")?.value
    // Real ES-DE's own datetime schema has no gameselectorEntry property
    // (confirmed against EsDeTheme.kt's schema) -- implicit entry 0,
    // same real convention already used for text's own metadata binding.
    val rawDate = when (metadata) {
        "releasedate" -> gameSelection.getOrNull(0)?.releaseDate
        else -> null
    }
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "%Y-%m-%d"
    val formatted = remember(rawDate, format) {
        rawDate?.let {
            runCatching {
                val sourceFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                sourceFormat.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sourceFormat.parse(it) ?: return@runCatching null
                SimpleDateFormat(strftimeToJavaPattern(format), Locale.getDefault()).format(parsed)
            }.getOrNull()
        }
    } ?: element.valueOrNull<EsDeThemeValue.Str>("defaultValue")?.value
    // Same real ":space:" blank-token convention (case-insensitive) as
    // EsDeThemedText -- decaffe's own gamelbl datetime uses it as its
    // defaultValue, which rendered the literal ":SPACE:" on-device for
    // any game with no release date.
    if (formatted.isNullOrBlank() || formatted.equals(":space:", ignoreCase = true)) return

    val hasSize = element.valueOrNull<EsDeThemeValue.Pair>("size") != null
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(
        element, viewWidth, viewHeight,
        if (hasSize) width else 0.dp, if (hasSize) height else 0.dp,
    )
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Same real DateTimeComponent default (0.035) as EsDeThemedClock.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    // Real background box -- `datetime` shares `backgroundColor` and
    // `backgroundCornerRadius` with `clock` (both are the same real
    // DateTimeComponent), so the same helper applies. Its own third real
    // property, `backgroundMargins`, is NOT part of that group and is
    // deliberately left unimplemented: none of the ten themes measured for
    // this pass declares it, and it is not the same (leading, trailing)
    // pair the clock's own padding properties are.
    val background = backgroundBoxOf(element, viewWidth, viewHeight, opacity)
    Text(
        text = formatted,
        color = color.copy(alpha = color.alpha * opacity),
        fontSize = fontSizeSp,
        fontFamily = themeFontFamily(element),
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .esDeBackgroundBox(background),
    )
}

/**
 * Real, honestly PARTIAL `badges` rendering. Real ES-DE's own
 * `BadgeComponent` shows up to nine real slot types (collection/folder/
 * favorite/completed/kidgame/broken/controller/altemulator/manual,
 * confirmed from `BadgeComponent.cpp`'s own constructor -- that's the
 * real default `<slots>` set when a theme declares none), laid out via a
 * real flexbox (`lines`/`itemsPerLine`/`itemMargin`). droidtop's
 * [LibraryEntry] only models ONE of those nine real flags --
 * [LibraryEntry.favorite] -- so this renders at most that single real
 * badge, positioned directly at the element's own `pos`/`size` (a real,
 * deliberate simplification: a full multi-badge flexbox layout for data
 * droidtop doesn't have would be real code with nothing real to lay out).
 * Every other real slot a theme's `<slots>` might request is a real,
 * honest, unrendered gap, not silently faked as absent-vs-false.
 *
 * Icon: the theme's own real `customBadgeIcon` when declared (common --
 * both DEcaffe and Art Book Next bundle real favorite-badge art) --
 * real ES-DE's OWN default badge icon set is compiled into its binary
 * as Qt resources (`:/graphics/badge_favorite.svg` etc.), which droidtop
 * has no access to and doesn't bundle a copy of (real licensing/IP
 * reason to avoid, not an oversight) -- falls back to a plain unicode
 * star, the same honest-fallback convention [EsDeThemedRating] already
 * uses.
 */
private val BADGE_GLYPHS = mapOf(
    "favorite" to "★",
    "completed" to "✔",
    "kidgame" to "☺",
    "broken" to "⚠",
    "controller" to "🎮",
    "altemulator" to "⚙",
    "manual" to "📖",
    "collection" to "🗂",
)

/**
 * Real, full flexbox badge layout -- ported from `BadgeComponent.cpp`/
 * `FlexboxComponent.cpp`'s own real `calculateLayout` (a real local clone
 * kept at /root/es-de-reference for ongoing reference), not the earlier
 * single-slot version. Real, honest simplifications (both deliberate, not
 * oversights):
 * - Every cell is rendered as a SQUARE of `min(maxItemWidth,
 *   maxItemHeight)`, not a per-image-aspect-ratio-adjusted size. Real
 *   ES-DE's own algorithm lets the FIRST visible item's real decoded
 *   image aspect ratio dictate uniform cell width for the rest --
 *   droidtop's own icons are plain unicode glyphs (same real licensing/
 *   IP reason favorite-only rendering already used) or, when a theme
 *   supplies a real `customBadgeIcon`, an async-loaded image whose real
 *   pixel size isn't known synchronously at layout time the way a
 *   blocking C++ image load's is -- a square cell is the honest
 *   approximation for both cases.
 * - `horizontalAlignment="center"` centers the WHOLE grid uniformly
 *   rather than real ES-DE's own per-row centering (which shifts a
 *   partially-filled last row separately from full rows above it) --
 *   only visibly different when the badge count doesn't evenly divide
 *   `itemsPerLine`.
 * - The real "controller" slot's own per-game controller-specific
 *   OVERLAY icon (`setBadges`' own real runtime texture swap) has no
 *   droidtop asset to render -- shows the generic controller glyph only,
 *   same honest gap as the rest of this function's glyph fallbacks.
 * - `folder` (the last of the 9 real slots) is never active -- see
 *   [dev.droidtop.library.theme.BADGE_SLOTS]'s own doc comment for why
 *   (real ES-DE gamelist subfolders have no droidtop equivalent --
 *   droidtop's ROM scan is flat -- unlike `collection`, which IS wired,
 *   see [dev.droidtop.library.LibraryEntry.inCollection]).
 */
@Composable
private fun EsDeThemedBadges(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val entry = gameSelection.getOrNull(0) ?: return

    val slotsRaw = element.valueOrNull<EsDeThemeValue.Str>("slots")?.value?.lowercase()
    val requestedSlots: List<String> = if (slotsRaw == null) {
        BADGE_SLOTS
    } else {
        val tokens = slotsRaw.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (tokens.contains("all")) {
            tokens.filter { it in BADGE_SLOTS } + BADGE_SLOTS.filter { it !in tokens }
        } else {
            tokens.filter { it in BADGE_SLOTS }
        }
    }

    fun isActive(slot: String): Boolean = when (slot) {
        "favorite" -> entry.favorite
        "completed" -> entry.completed
        "kidgame" -> entry.kidGame
        "broken" -> entry.broken
        "controller" -> entry.controllerShortName != null
        "altemulator" -> entry.altEmulator != null
        "manual" -> entry.manualUri != null
        "collection" -> entry.inCollection
        else -> false // folder -- see this function's own doc comment
    }

    val activeSlots = requestedSlots.filter { isActive(it) }
    if (activeSlots.isEmpty()) return

    val direction = element.valueOrNull<EsDeThemeValue.Str>("direction")?.value ?: "row"
    val alignment = element.valueOrNull<EsDeThemeValue.Str>("horizontalAlignment")?.value ?: "left"
    val lines = (element.valueOrNull<EsDeThemeValue.UInt>("lines")?.value?.toInt() ?: 2).coerceIn(1, 10)
    var itemsPerLine = (element.valueOrNull<EsDeThemeValue.UInt>("itemsPerLine")?.value?.toInt() ?: 4).coerceIn(1, 10)
    // Real ES-DE behavior: too many active badges for the declared grid
    // widens itemsPerLine to fit, rather than clipping/overflowing.
    if (itemsPerLine * lines < activeSlots.size) itemsPerLine = activeSlots.size

    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)

    val itemMarginRaw = element.valueOrNull<EsDeThemeValue.Pair>("itemMargin")
    // Real ES-DE default: 1% of the real screen's own width/height --
    // viewWidth/viewHeight (this element's own view, usually close to
    // full-screen for the system/gamelist views badges actually appear
    // in) stands in for that, an honest approximation, not a literal
    // display-metrics query.
    val itemMarginX = if (itemMarginRaw != null) viewWidth * itemMarginRaw.x else viewWidth * 0.01f
    val itemMarginY = if (itemMarginRaw != null) viewHeight * itemMarginRaw.y else viewHeight * 0.01f

    val gridX = if (direction == "row") itemsPerLine else lines
    val gridY = if (direction == "row") lines else itemsPerLine

    val maxItemWidth = (width + itemMarginX - itemMarginX * gridX) / gridX
    val maxItemHeight = (height + itemMarginY - itemMarginY * gridY) / gridY
    val cellSize = if (maxItemWidth < maxItemHeight) maxItemWidth else maxItemHeight

    val alignOffsetX = when {
        alignment == "right" && direction == "row" ->
            width - (cellSize + itemMarginX) * gridX + itemMarginX
        alignment == "center" && direction == "row" ->
            (width - (cellSize + itemMarginX) * gridX + itemMarginX) / 2f
        else -> 0.dp
    }

    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val badgeColor = element.valueOrNull<EsDeThemeValue.Color>("badgeIconColor")?.let { colorOf(it) } ?: Color.White
    val density = LocalDensity.current

    activeSlots.forEachIndexed { index, slot ->
        val gx: Int
        val gy: Int
        if (direction == "row") {
            gx = index % gridX
            gy = index / gridX
        } else {
            gx = index / gridY
            gy = index % gridY
        }
        val cellX = offsetX + alignOffsetX + (cellSize + itemMarginX) * gx
        val cellY = offsetY + (cellSize + itemMarginY) * gy

        val customIcon = element.valueOrNull<EsDeThemeValue.Path>("badge_$slot")?.resolved
        if (customIcon != null) {
            AsyncImage(
                model = customIcon,
                contentDescription = null,
                modifier = Modifier.absoluteOffset(x = cellX, y = cellY).size(cellSize).graphicsLayer { alpha = opacity },
            )
        } else {
            Text(
                text = BADGE_GLYPHS[slot] ?: "?",
                color = badgeColor.copy(alpha = badgeColor.alpha * opacity),
                fontSize = with(density) { cellSize.toSp() },
                modifier = Modifier.absoluteOffset(x = cellX, y = cellY),
            )
        }
    }
}

/**
 * Real, honestly PARTIAL `systemstatus` rendering. Real ES-DE's own
 * `SystemStatusComponent` shows up to four real entry types (wifi/
 * cellular/bluetooth/battery, confirmed from `SystemStatusComponent.cpp`'s
 * own real `entries` handling). droidtop genuinely IS the host device
 * (unlike ES-DE's own desktop OS-status queries, this is real, live
 * on-device status, not fabricated) -- wifi/cellular via
 * `ConnectivityManager.getNetworkCapabilities` (real `ACCESS_NETWORK_STATE`,
 * declared in :app's own manifest), and
 * battery percent/charging via the real sticky `ACTION_BATTERY_CHANGED`
 * broadcast (no permission needed at all). Bluetooth is a real, deliberate
 * gap: reading adapter state needs `BLUETOOTH_CONNECT`, a dangerous
 * Android 12+ runtime permission -- not worth requesting for one
 * decorative status icon without checking with the user first, so it's
 * simply never included regardless of what a theme's own `entries`
 * property requests.
 *
 * Polled every 3s (matching [EsDeThemedClock]'s own live-tick pattern) --
 * real device status genuinely changes over time, unlike per-game data.
 * Icons come from the theme's own real `customIcon` declarations when it
 * ships them (real ES-DE's per-entry-type `icon_wifi`/`icon_cellular`/
 * `icon_battery_*`/`icon_bluetooth` attribute scheme, four of the ten
 * themes measured for this pass ship a full set), falling back to plain
 * unicode glyphs otherwise -- real ES-DE's OWN defaults are bundled
 * Qt-resource SVGs droidtop has no copy of, the same real licensing/IP
 * reason [EsDeThemedBadges] documents.
 */
@Composable
private fun EsDeThemedSystemStatus(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val context = LocalContext.current
    val entriesRaw = element.valueOrNull<EsDeThemeValue.Str>("entries")?.value?.lowercase()
    val entries = entriesRaw?.split(Regex("[,\\s]+"))?.filter { it.isNotBlank() }
        ?: listOf("wifi", "cellular", "battery")
    val showAll = entries.contains("all")

    var wifiConnected by remember { mutableStateOf(false) }
    var cellularConnected by remember { mutableStateOf(false) }
    var batteryPercent by remember { mutableStateOf<Int?>(null) }
    var batteryCharging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            cellularConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            delay(3000)
        }
    }

    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    // Real ES-DE property for this element specifically is "height" (a
    // single float, no "size" pair at all -- confirmed against its real
    // schema), unlike most other element types.
    val heightFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("height")?.value ?: 0.03f
    val heightDp = (heightFraction * viewHeight.value).dp
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, height = heightDp)

    // Real `customIcon` (SystemStatusComponent.cpp:310-330): an
    // attribute-keyed PATH per status icon, whose real attribute values
    // are literally "icon_wifi"/"icon_cellular"/"icon_battery_charging"/
    // "icon_battery_low"/"icon_battery_medium"/"icon_battery_high"/
    // "icon_battery_full"/"icon_bluetooth" -- so the parser's own
    // attribute-keyed storage key (see ES_DE_PROPERTY_ATTRIBUTE_MAP) is
    // "customIcon_icon_wifi" and so on. Real ES-DE's own defaults are
    // bundled Qt-resource SVGs droidtop has no copy of (the same real
    // licensing reason EsDeThemedBadges documents), so a theme's own icons
    // are the ONLY real art available here -- without this the four
    // measured themes that ship a full status-icon set still rendered
    // unicode glyphs.
    fun customIcon(name: String): String? =
        element.valueOrNull<EsDeThemeValue.Path>("customIcon_icon_$name")?.resolved?.takeIf { File(it).exists() }

    // Real battery-level bands, transcribed from
    // SystemStatusComponent.cpp:88-97: charging wins outright, then
    // 0-25 low, 26-60 medium, 61-90 high, anything else full.
    fun batteryIconName(): String = when {
        batteryCharging -> "battery_charging"
        (batteryPercent ?: 0) <= 25 -> "battery_low"
        (batteryPercent ?: 0) <= 60 -> "battery_medium"
        (batteryPercent ?: 0) <= 90 -> "battery_high"
        else -> "battery_full"
    }

    // Each real entry is either the theme's own icon file or the unicode
    // fallback glyph droidtop has always used.
    val parts = buildList<kotlin.Pair<String?, String>> {
        if (showAll || entries.contains("wifi")) { if (wifiConnected) add(customIcon("wifi") to "📶") }
        if (showAll || entries.contains("cellular")) { if (cellularConnected) add(customIcon("cellular") to "📱") }
        if (showAll || entries.contains("battery")) {
            batteryPercent?.let {
                add(customIcon(batteryIconName()) to ((if (batteryCharging) "⚡" else "🔋") + "$it%"))
            }
        }
    }
    if (parts.isEmpty()) return

    // Real `entrySpacing` (SystemStatusComponent.cpp:304-308) -- a
    // fraction of screen WIDTH, the same axis real ES-DE uses for the help
    // bar's own entrySpacing.
    val entrySpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("entrySpacing")?.value ?: 0.005f) * viewWidth.value
    val background = backgroundBoxOf(element, viewWidth, viewHeight, opacity)
    Row(
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .esDeBackgroundBox(background),
        horizontalArrangement = Arrangement.spacedBy(entrySpacing.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { (iconPath, glyph) ->
            if (iconPath != null) {
                AsyncImage(
                    model = iconPath,
                    contentDescription = null,
                    // Real SystemStatusComponent.cpp:100/:107 -- every icon
                    // gets the element's own `color` as a colorSHIFT and is
                    // resized to the element's own height with its aspect
                    // ratio preserved (setResize(0, mSize.y)).
                    colorFilter = ColorFilter.tint(color, BlendMode.Modulate),
                    alpha = opacity,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(heightDp),
                )
            } else {
                Text(
                    text = glyph,
                    color = color.copy(alpha = color.alpha * opacity),
                    fontSize = with(LocalDensity.current) { heightDp.toSp() },
                    fontFamily = themeFontFamily(element),
                )
            }
        }
    }
}

/**
 * Real, honestly PARTIAL `gamelistinfo` rendering. Real ES-DE's own
 * `GamelistView::onFileChanged` (the actual source, confirmed directly)
 * builds this string as, in the real unfiltered case: a controller-glyph
 * icon + the system's total real game count, plus a separate favorites
 * count when any exist. Two real real cases NOT covered here, both
 * genuinely separate features droidtop doesn't have yet: the filtered
 * case (`N + M / Total`, needs a real gamelist filter UI -- the
 * headless/widget gamelist screens this renders on have no filtering at
 * all) and the folder-entered case (a folder-char prefix -- droidtop's
 * own real game groups are flat, no folder concept). [entries] is the
 * real, FULL per-system game list ([focusedSystemEntries], not
 * `gameSelection`) -- gamelistinfo's whole point is a total count, not a
 * per-selected-game value.
 */
@Composable
private fun EsDeThemedGamelistInfo(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, entries: List<LibraryEntry>) {
    if (entries.isEmpty()) return
    val favoriteCount = entries.count { it.favorite }
    val text = if (favoriteCount > 0) "${entries.size} games  ♥ $favoriteCount" else "${entries.size} games"

    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeDp = (fontSizeFraction * viewHeight.value).dp
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)

    Text(
        text = text,
        color = color.copy(alpha = color.alpha * opacity),
        fontSize = with(LocalDensity.current) { fontSizeDp.toSp() },
        fontFamily = themeFontFamily(element),
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

/**
 * Real `rating` rendering -- [LibraryEntry.rating] (0.0-1.0, the same
 * real convention real ES-DE's own scrapers already normalize to, see
 * that field's own doc comment) drives a real 5-star row. Real ES-DE's
 * own RatingComponent geometry. When the theme declares `filledPath`/`unfilledPath`
 * this now renders real ES-DE's own CONTINUOUS clip geometry (a 0.7
 * rating really is three and a half stars) and honors `overlay` and
 * `interpolation` -- see the geometry citations inline. Falls back to a
 * plain unicode star string when the theme ships no star art, which can
 * only show whole stars; that fallback is an honest simplification, not a
 * full per-theme custom-star-shape renderer.
 */
@Composable
private fun EsDeThemedRating(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val rating = gameSelection.getOrNull(0)?.rating ?: return
    val hideIfZero = element.valueOrNull<EsDeThemeValue.Bool>("hideIfZero")?.value ?: false
    if (hideIfZero && rating <= 0f) return
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val filledPath = element.valueOrNull<EsDeThemeValue.Path>("filledPath")?.resolved
    val unfilledPath = element.valueOrNull<EsDeThemeValue.Path>("unfilledPath")?.resolved
    val filterQuality = esDeFilterQuality(element) ?: FilterQuality.Low

    if (filledPath != null && unfilledPath != null) {
        // Real RatingComponent geometry (RatingComponent.cpp:79-83 and the
        // identical block at :160-163): the icons are ONE tiled image row
        // spanning the element's whole width, and the rating clips the
        // filled row at round(width * value) -- a CONTINUOUS clip, so a
        // 0.7 rating really does render three and a half stars. droidtop
        // previously rounded to whole stars, which showed the wrong count
        // for most real scraped ratings.
        //
        // Real `overlay` (RatingComponent.cpp:294-295, default true at
        // :24): with overlay ON the unfilled row is drawn full width and
        // the filled row painted over it; with overlay OFF the unfilled
        // row is ALSO clipped, to start exactly where the filled row ends
        // (RatingComponent.cpp:81-82), so the two abut instead of
        // overlapping. That matters for semi-transparent icon art, where
        // overlapping doubles the alpha.
        val overlay = element.valueOrNull<EsDeThemeValue.Bool>("overlay")?.value ?: true
        val filledWidth = width * rating.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .absoluteOffset(x = offsetX, y = offsetY)
                .size(width = width, height = height)
                .graphicsLayer { alpha = opacity },
        ) {
            Box(
                modifier = Modifier
                    .absoluteOffset(x = if (overlay) 0.dp else filledWidth)
                    .size(width = if (overlay) width else width - filledWidth, height = height)
                    .clipToBounds(),
            ) {
                EsDeRatingIconRow(
                    path = unfilledPath,
                    rowWidth = width,
                    height = height,
                    // The unfilled row is always drawn from the row's own
                    // left edge; when it is clipped it is the BOX that
                    // moves, not the icons inside it.
                    shiftX = if (overlay) 0.dp else -filledWidth,
                    filterQuality = filterQuality,
                )
            }
            Box(
                modifier = Modifier
                    .size(width = filledWidth, height = height)
                    .clipToBounds(),
            ) {
                EsDeRatingIconRow(
                    path = filledPath,
                    rowWidth = width,
                    height = height,
                    shiftX = 0.dp,
                    filterQuality = filterQuality,
                )
            }
        }
    } else {
        // No theme-supplied star art: the same honest unicode fallback as
        // before, which can only show whole stars.
        val filledStars = kotlin.math.round(rating * 5).toInt().coerceIn(0, 5)
        val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
        Text(
            text = "★".repeat(filledStars) + "☆".repeat(5 - filledStars),
            color = color.copy(alpha = color.alpha * opacity),
            fontSize = with(LocalDensity.current) { height.toSp() },
            modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
        )
    }
}

/**
 * One full-width row of five identical rating icons -- real ES-DE tiles a
 * single icon texture NUM_RATING_STARS times across the element
 * (RatingComponent.cpp's own mIconFilled/mIconUnfilled are tiled images
 * the width of the whole component), which is what makes the fractional
 * clip above land inside a star rather than between two.
 */
@Composable
private fun EsDeRatingIconRow(
    path: String,
    rowWidth: Dp,
    height: Dp,
    shiftX: Dp,
    filterQuality: FilterQuality,
) {
    Row(
        modifier = Modifier
            .absoluteOffset(x = shiftX)
            // requiredSize, not size: this row is deliberately wider than
            // the clipping Box it sits inside, and plain size() would be
            // clamped by that Box's constraints -- which is exactly the
            // clip the fractional geometry depends on.
            .requiredSize(width = rowWidth, height = height),
    ) {
        repeat(5) {
            AsyncImage(
                model = path,
                contentDescription = null,
                filterQuality = filterQuality,
                contentScale = ContentScale.Fit,
                modifier = Modifier.requiredSize(width = rowWidth / 5, height = height),
            )
        }
    }
}

/**
 * Real, theme-styled button-hint bar -- reads the theme's own real
 * `<helpsystem>` pos/origin/textColor/iconColor/fontSize/entrySpacing/
 * backgroundColor/opacity (see EsDeTheme.kt's schema), applied to
 * whichever [hints] the caller says are currently valid for this screen.
 * Also real and applied: `backgroundHorizontalPadding`/
 * `backgroundVerticalPadding`/`backgroundCornerRadius` (see
 * [EsDeBackgroundBox]), `iconTextSpacing` and `entryRelativeScale`.
 *
 * Honestly still unimplemented, and NOT faked: `customButtonIcon` (real
 * per-theme glyph art for each button, which would replace the platform
 * button LABEL this draws -- a separate piece of work), and the whole
 * `*Dimmed` family (`posDimmed`/`originDimmed`/`textColorDimmed`/
 * `iconColorDimmed`/`fontSizeDimmed`/`opacityDimmed`/`entrySpacingDimmed`/
 * `iconTextSpacingDimmed`). Real ES-DE switches to those only while its
 * own menu overlay is dimming the background
 * (HelpComponent.cpp:76-80, `mWindow->isBackgroundDimmed()`); droidtop
 * renders no such overlay at all -- `scope=menu` help declarations are
 * skipped outright, see [EsDeThemedView] -- so there is no real state to
 * key them off, and implementing them would mean inventing one.
 */
@Composable
private fun EsDeThemedHelpSystem(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    hints: List<kotlin.Pair<GamepadAction, String>>,
) {
    if (hints.isEmpty()) return
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    // Real HelpComponent.cpp defaults (0x777777FF, gray) for BOTH colors --
    // droidtop previously guessed White/Black, confirmed wrong against real
    // ES-DE source: a theme that sets only one of textColor/iconColor would
    // have paired it with an arbitrary, wrong color for the other.
    val defaultHelpColor = Color(0xFF777777)
    val textColor = element.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: defaultHelpColor
    val iconColor = element.valueOrNull<EsDeThemeValue.Color>("iconColor")?.let { colorOf(it) } ?: defaultHelpColor
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Real default: HelpComponent constructs with FONT_SIZE_SMALL (0.035),
    // not 0.025 -- confirmed against real ES-DE source (Font.h/HelpComponent.h).
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    // Real ES-DE HelpComponent.cpp: entrySpacing is a fraction of screen
    // WIDTH, not height (droidtop previously used height, matching every
    // other element's own real height-based convention, but help-bar
    // spacing is confirmed real-source to be the one exception) -- real
    // default is 0.00833, not 0.02 (droidtop's own earlier guess was
    // ~2.4x too large).
    val entrySpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("entrySpacing")?.value ?: 0.00833f) * viewWidth.value
    // Real `iconTextSpacing` (HelpComponent.cpp:277-278) -- the gap
    // between one entry's own button glyph and its label, clamped to
    // 0..0.04 and, like entrySpacing, a fraction of screen WIDTH
    // (HelpComponent.cpp:687). Real default 0.00416
    // (HelpComponent.cpp:47); droidtop previously hardcoded 6dp.
    val iconTextSpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("iconTextSpacing")?.value ?: 0.00416f)
        .coerceIn(0f, 0.04f) * viewWidth.value
    // Real `entryRelativeScale` (HelpComponent.cpp:182-183), clamped to
    // 0.2..3.0. Real ES-DE only ever applies it to SHRINK the label font
    // (HelpComponent.cpp:194-200, all three branches gated on < 1.0) --
    // above 1.0 the font is left alone and the ICON is what changes,
    // divided by the scale so it grows relative to the text
    // (HelpComponent.cpp:664-667). Both halves are reproduced here; a
    // value of exactly 1.0 is a no-op either way.
    val entryRelativeScale = (element.valueOrNull<EsDeThemeValue.FloatValue>("entryRelativeScale")?.value ?: 1f)
        .coerceIn(0.2f, 3.0f)
    val labelFontSizeSp = if (entryRelativeScale < 1f) fontSizeSp * entryRelativeScale else fontSizeSp
    val iconFontSizeSp = if (entryRelativeScale < 1f) fontSizeSp else fontSizeSp / entryRelativeScale
    // Real background box -- same group, same geometry, same source as
    // clock/systemstatus (HelpComponent.cpp:133-180). droidtop previously
    // drew backgroundColor as a bare, padding-less fill.
    val background = backgroundBoxOf(element, viewWidth, viewHeight, opacity)

    Row(
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .esDeBackgroundBox(background)
            .graphicsLayer { alpha = opacity },
        horizontalArrangement = Arrangement.spacedBy(entrySpacing.dp),
    ) {
        hints.forEach { (action, label) ->
            // Real ES-DE HelpComponent draws an icon glyph in `iconColor`
            // beside a text label in `textColor` -- no pill/background
            // behind the glyph at all. Real, confirmed-live bug this
            // fixes: droidtop drew the glyph IN iconColor ON a pill
            // filled with textColor -- decaffe keys both colors to the
            // same real palette family (see colors.xml's own
            // mainFontColor-driven scheme), so the glyph text and its own
            // background were the same color, rendering as a blank,
            // illegible circle on every real device screenshot.
            val fontFamily = themeFontFamily(element)
            Row(
                horizontalArrangement = Arrangement.spacedBy(iconTextSpacing.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(GamepadKeyMap.labelFor(action), color = iconColor, fontSize = iconFontSizeSp, fontFamily = fontFamily)
                Text(label, color = textColor, fontSize = labelFontSizeSp, fontFamily = fontFamily)
            }
        }
    }
}

/**
 * `origin` is ES-DE's real anchor-point convention: a 0-1 fraction of the
 * element's OWN size that `pos` refers to, not always the top-left corner
 * (DEcaffe uses `origin="0.5 0.5"` -- center-anchoring -- throughout).
 * Defaults to "0 0" (top-left), matching ES-DE's own default when an
 * element omits the property.
 */
/**
 * ES-DE's real `datetime`/`clock` `format` property uses C strftime-style
 * `%`-specifiers (its own renderer calls through to strftime under the
 * hood), not Java's SimpleDateFormat letters -- feeding one straight into
 * SimpleDateFormat produced real garbage (e.g. a real theme's "%H:%M"-style
 * default rendered as literal "%2026", since SimpleDateFormat treats '%' as
 * a literal character and 'Y'/'H'/etc. as its OWN unrelated pattern
 * letters). Covers the common specifiers ES-DE's own themes actually use;
 * anything else passes through as a SimpleDateFormat literal (quoted), the
 * same honest-fallback approach used elsewhere in this renderer.
 */
private val STRFTIME_TO_JAVA = listOf(
    "%Y" to "yyyy", "%y" to "yy",
    "%m" to "MM", "%d" to "dd",
    "%H" to "HH", "%I" to "hh",
    "%M" to "mm", "%S" to "ss",
    "%p" to "a",
    "%A" to "EEEE", "%a" to "EEE",
    "%B" to "MMMM", "%b" to "MMM",
    "%%" to "%",
)

private fun strftimeToJavaPattern(format: String): String {
    val sb = StringBuilder()
    var i = 0
    outer@ while (i < format.length) {
        for ((strftime, java) in STRFTIME_TO_JAVA) {
            if (format.startsWith(strftime, i)) {
                sb.append(java)
                i += strftime.length
                continue@outer
            }
        }
        val c = format[i]
        if (c.isLetter()) sb.append('\'').append(c).append('\'') else sb.append(c)
        i++
    }
    return sb.toString()
}

private fun positionOf(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    width: Dp = 0.dp,
    height: Dp = 0.dp,
): kotlin.Pair<Dp, Dp> {
    val pos = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f)
    val origin = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val x = viewWidth * pos.x - width * origin.x
    val y = viewHeight * pos.y - height * origin.y
    return x to y
}

/**
 * Real, distinct ES-DE properties (confirmed against ImageComponent.cpp's
 * own setResize/setMaxSize): "size" stretches to an exact size, "maxSize"
 * scales down to fit WITHIN bounds preserving aspect ratio -- a real
 * theme can use only one (Art Book Next's own system-logo element has no
 * "size" at all, only "maxSize"). "size" wins if both are present,
 * matching ES-DE's own real precedence (its applyTheme sets one or the
 * other, "size" checked first). The maxSize case doesn't yet apply real
 * aspect-preserving containment at this layer -- AsyncImage's own
 * ContentScale.Fit (already applied at the call site) does that visually
 * within whatever box these bounds define, an honest approximation since
 * this parser doesn't decode the target image's real intrinsic size.
 */
private fun sizeOf(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp): kotlin.Pair<Dp, Dp> {
    val size = element.valueOrNull<EsDeThemeValue.Pair>("size")
        ?: element.valueOrNull<EsDeThemeValue.Pair>("maxSize")
        ?: EsDeThemeValue.Pair(0.2f, 0.2f)
    return viewWidth * size.x to viewHeight * size.y
}

private fun zIndexOf(element: EsDeThemeElement): Float =
    element.valueOrNull<EsDeThemeValue.FloatValue>("zIndex")?.value ?: 0f

/** [EsDeThemeValue.Color.argbLikeRgba] is packed RRGGBBAA (same layout as ES-DE's own getHexColor) -- Compose's Color wants ARGB, so the channels need reordering, not just a straight reinterpret. Internal: EsDeSystemListView shares this exact conversion (it used to keep its own private copy -- one job, one implementation). */
internal fun colorOf(value: EsDeThemeValue.Color): Color {
    val rgba = value.argbLikeRgba
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return Color(red = r.toInt(), green = g.toInt(), blue = b.toInt(), alpha = a.toInt())
}

// Real, confirmed-live gap this fixes: `fontPath` is a real, correctly-
// parsed property (every text-bearing element type declares it -- see
// EsDeTheme.kt's own real schema) that no renderer here ever actually
// LOADED and applied -- every themed screen rendered in Compose's plain
// system default font regardless of what real font file a theme bundles
// and points `fontPath` at (decaffe alone ships 8 real .otf/.ttf files,
// referenced by ~40 separate `fontPath` declarations across its own
// theme.xml). Cached by resolved file path -- `Font(File)` decodes the
// real font file from disk, not free to redo on every recomposition.
// Not `@Composable`: a plain memoization map is enough here, no Compose
// state/recomposition scoping needed for a value keyed purely off the
// element's own already-resolved path.
private val themeFontFamilyCache = mutableMapOf<String, FontFamily>()

internal fun themeFontFamily(element: EsDeThemeElement): FontFamily? {
    val path = element.valueOrNull<EsDeThemeValue.Path>("fontPath")?.resolved ?: return null
    return themeFontFamilyCache.getOrPut(path) {
        FontFamily(Font(File(path)))
    }
}


/**
 * Real ES-DE `interpolation` (`image`/`video`/`rating`/`badges`) and the
 * `imageInterpolation` spelling of the identical real property on
 * `carousel`/`grid`: "nearest" turns texture filtering off, "linear"
 * turns it on. Transcribed from ImageComponent.cpp:590-598 (and the
 * character-for-character identical blocks at VideoComponent.cpp:270-284
 * and RatingComponent.cpp:248-256) -- exactly two accepted literals, any
 * other value logged and ignored.
 *
 * One honest, deliberate divergence rather than a silent one: real
 * ES-DE's flag drives `setLinearMagnify` (VideoFFmpegComponent.cpp:1459
 * and the equivalent TextureResource call for images), which is the
 * MAGNIFICATION filter only -- minification stays filtered/mipmapped.
 * Compose's [FilterQuality] is a single knob covering both, so "nearest"
 * here also unfilters images that are scaled DOWN. That is why this
 * returns null (keep Compose's own filtered default) when a theme
 * declares nothing at all, instead of returning FilterQuality.None to
 * match real ES-DE's `mLinearInterpolation {false}` member default
 * (ImageComponent.cpp:43): pushing the real default through the wrong
 * knob would unfilter every downscaled image in every theme, a bigger
 * error than not applying an unset default. The same reasoning leaves
 * ImageComponent.cpp:582-588's own "arbitrary rotation implies linear"
 * rule out -- it only ever turns filtering ON, which is already the
 * Compose default here.
 */
internal fun esDeFilterQuality(element: EsDeThemeElement, property: String = "interpolation"): FilterQuality? =
    when (element.valueOrNull<EsDeThemeValue.Str>(property)?.value) {
        "nearest" -> FilterQuality.None
        "linear" -> FilterQuality.Low
        else -> null
    }

/**
 * Real ES-DE background-box group -- `backgroundColor`,
 * `backgroundHorizontalPadding`, `backgroundVerticalPadding` and
 * `backgroundCornerRadius`, parsed identically by the three element types
 * that own them: `clock` (DateTimeComponent.cpp:294-313), `systemstatus`
 * (SystemStatusComponent.cpp:233-283) and `helpsystem`
 * (HelpComponent.cpp:133-180).
 *
 * Real geometry, transcribed from DateTimeComponent.cpp:163-175: the box
 * is drawn at (-horizontalPadding.x, -verticalPadding.x) relative to
 * the element's own already-resolved position, sized
 * size.x + horizontalPadding.x + horizontalPadding.y by
 * size.y + verticalPadding.x + verticalPadding.y. So each padding pair
 * is (LEADING, TRAILING) on its own axis -- NOT a width/height pair, the
 * obvious wrong reading. Both components of `backgroundHorizontalPadding`
 * scale against screen WIDTH and both components of
 * `backgroundVerticalPadding` against screen HEIGHT
 * (DateTimeComponent.cpp:297-307), each clamped to 0..1;
 * `backgroundCornerRadius` is clamped to 0..0.5 and scales against screen
 * WIDTH (DateTimeComponent.cpp:310-313) -- the same width-axis exception
 * already confirmed for image `cornerRadius` and help-bar `entrySpacing`.
 *
 * `backgroundColorEnd`/`backgroundGradientType` are deliberately left
 * out: they are the same positional-gradient shape as the
 * `image*ColorEnd` family, which a Compose ColorFilter cannot express
 * (it needs a real shader), so implementing only the flat-color half is
 * the honest subset.
 */
private data class EsDeBackgroundBox(
    val color: Color?,
    val padStart: Dp,
    val padEnd: Dp,
    val padTop: Dp,
    val padBottom: Dp,
    val cornerRadius: Dp,
)

private fun backgroundBoxOf(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    opacity: Float,
): EsDeBackgroundBox {
    val raw = element.valueOrNull<EsDeThemeValue.Color>("backgroundColor")?.let { colorOf(it) }
    val color = raw?.copy(alpha = raw.alpha * opacity)
    val h = element.valueOrNull<EsDeThemeValue.Pair>("backgroundHorizontalPadding")
    val v = element.valueOrNull<EsDeThemeValue.Pair>("backgroundVerticalPadding")
    val radius = (element.valueOrNull<EsDeThemeValue.FloatValue>("backgroundCornerRadius")?.value ?: 0f)
        .coerceIn(0f, 0.5f)
    return EsDeBackgroundBox(
        color = color,
        padStart = viewWidth * (h?.x ?: 0f).coerceIn(0f, 1f),
        padEnd = viewWidth * (h?.y ?: 0f).coerceIn(0f, 1f),
        padTop = viewHeight * (v?.x ?: 0f).coerceIn(0f, 1f),
        padBottom = viewHeight * (v?.y ?: 0f).coerceIn(0f, 1f),
        cornerRadius = (radius * viewWidth.value).dp,
    )
}

/**
 * Applies an [EsDeBackgroundBox] so the WRAPPED CONTENT still lands at
 * the element's own resolved position: the box itself shifts up/left by
 * the leading pads and then pads the content back down/right by the same
 * amounts, reproducing real ES-DE's translate(-padding.x, -padding.y)
 * followed by a rect of size + leading + trailing
 * (DateTimeComponent.cpp:163-175). A no-op when the theme declares no
 * `backgroundColor` -- real ES-DE's own guard is exactly that
 * (mClockBgColor != 0x00000000, DateTimeComponent.cpp:163).
 */
private fun Modifier.esDeBackgroundBox(box: EsDeBackgroundBox): Modifier {
    if (box.color == null) return this
    return this
        .absoluteOffset(x = -box.padStart, y = -box.padTop)
        .let { if (box.cornerRadius > 0.dp) it.clip(RoundedCornerShape(box.cornerRadius)) else it }
        .background(box.color)
        .padding(start = box.padStart, end = box.padEnd, top = box.padTop, bottom = box.padBottom)
}

/**
 * Real ES-DE `scrollFadeIn` (`image` and `video`,
 * ImageComponent.cpp:764-765 / VideoComponent.cpp:448-449, consumed by
 * GamelistView.cpp:896-911): when the gamelist cursor lands on a new
 * game, the element fades from FADE_IN_START_OPACITY (0.5) up to full
 * over FADE_IN_TIME (325 ms) -- both real constants from
 * GamelistView.cpp:17-18. Keyed on the selected entry's own identity, so
 * it re-runs on each real selection change and not on every
 * recomposition.
 */
@Composable
private fun esDeScrollFadeInAlpha(element: EsDeThemeElement, key: Any?): Float {
    if (element.valueOrNull<EsDeThemeValue.Bool>("scrollFadeIn")?.value != true) return 1f
    var target by remember(key) { mutableStateOf(0.5f) }
    LaunchedEffect(key) { target = 1f }
    val alpha by animateFloatAsState(targetValue = target, animationSpec = tween(325), label = "scrollFadeIn")
    return alpha
}

/**
 * Real ES-DE `metadataElement` (ImageComponent.cpp:739-740,
 * TextComponent.cpp:471-472, VideoComponent.cpp:234-235): an element
 * flagged this way is hidden whenever the game's metadata fields are
 * being hidden -- GamelistView.cpp:697-704 sets `hideMetaDataFields` from
 * the entry's own real `hidemetadata` metadata flag, then hides every
 * flagged element. droidtop models exactly that flag
 * ([LibraryEntry.hideMetadata], user-editable in GameMetadataEditor), so
 * this is a real binding, not an approximation.
 *
 * Real ES-DE's SECOND trigger for the same flag -- browsing INTO a
 * grouped custom collection while the cursor is scrolling
 * (GamelistView.cpp:691-695) -- has no droidtop equivalent: its
 * collections are membership flags over a flat scan, never a nested
 * gamelist you enter, so that half is deliberately not reproduced.
 */
private fun esDeHiddenByMetadataFlag(element: EsDeThemeElement, entry: LibraryEntry?): Boolean =
    element.valueOrNull<EsDeThemeValue.Bool>("metadataElement")?.value == true && entry?.hideMetadata == true
