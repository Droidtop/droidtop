package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.droidtop.app.ui.PadButton
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.Measure
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TouchHintBar
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.standard.HomeRolePrefs

/**
 * droidtop's first-run tutorial (docs/SPEC.md 7b, "The first-run
 * tutorial"): what onboarding cannot ask about -- the controls, getting
 * around, the sections, launching, the Quick Menu, switching modes and
 * where help is.
 *
 * Shown once, over the first frame of the mode onboarding opens into, and
 * again whenever someone asks for it from Global settings ("Show the
 * tutorial"). Skippable from every page. Pad and touch drive it the same
 * way as every other droidtop screen: A and the filled button go on, B and
 * Back go back one page, and the hint row is tappable.
 *
 * Its content is written from the rig's new-user pass, which tried to get
 * stuck on purpose (dq-coordinator-24, "Tutorial should cover" and "Stuck
 * moments"), and it says only what this device has: a page about Gaming
 * appears only while Gaming is on, and the ways to switch modes are the
 * ones this setup actually has.
 */
class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        GamepadKeyMap.load(this)
        val pages = TutorialPages.forThisDevice(this)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    TutorialScreen(pages = pages, onDone = { finish() })
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, TutorialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** One page: a title, what it says, and the buttons or places it names. */
internal data class TutorialPage(
    val title: String,
    val body: String,
    val rows: List<TutorialRow> = emptyList(),
)

/** A thing to press or a place to look ([key]), and what it does. */
internal data class TutorialRow(val key: String, val does: String)

/**
 * The pages, for what is set up on this device. Pure over its inputs, so
 * what a given setup is told can be read in one place.
 */
internal object TutorialPages {

    fun forThisDevice(context: Context): List<TutorialPage> = build(
        gaming = Modes.isEnabledInStorage(context, Mode.GAMING),
        desktop = Modes.isEnabledInStorage(context, Mode.DESKTOP),
        home = HomeRolePrefs.activeHomeImplementation(context),
        homeGoesTo = Mode.byId(Modes.homeTarget(context)),
        defaultChosen = Modes.defaultMode(context) != null,
        key = GamepadKeyMap::labelFor,
    )

    fun build(
        gaming: Boolean,
        desktop: Boolean,
        home: HomeRolePrefs.HomeImplementation,
        homeGoesTo: Mode?,
        defaultChosen: Boolean,
        key: (GamepadAction) -> String,
    ): List<TutorialPage> = buildList {
        add(
            TutorialPage(
                title = "Getting around",
                body = "droidtop works with a controller, a keyboard or touch. No controller? Tap what " +
                    "you want. The bar along the bottom of every screen names what each button does " +
                    "there, and tapping a name in it presses that button.",
                rows = buildList {
                    add(TutorialRow(key(GamepadAction.A), "Choose, open, play"))
                    add(TutorialRow(key(GamepadAction.B), "Back, one step at a time"))
                    add(TutorialRow("D-pad", "Move the selection"))
                    if (gaming) {
                        add(TutorialRow("${key(GamepadAction.L)} ${key(GamepadAction.R)}", "Switch between Games, Apps and Settings"))
                        add(TutorialRow(key(GamepadAction.R2), "Open the Quick Menu"))
                        add(TutorialRow(key(GamepadAction.Y), "A game's page"))
                        add(TutorialRow(key(GamepadAction.X), "Add a game to your favourites"))
                        add(TutorialRow(key(GamepadAction.SELECT), "Options for the list you are in"))
                    }
                },
            ),
        )
        if (gaming) {
            add(
                TutorialPage(
                    title = "Your games",
                    body = "Gaming opens on Games: your game systems side by side. Move to one and press " +
                        "${key(GamepadAction.A)} to open its list, then ${key(GamepadAction.A)} on a game " +
                        "to play it. All games lists everything in one place. Windows games and games made " +
                        "with Ren'Py, RPG Maker, Kirikiri and similar engines are together on the PC card.",
                ),
            )
            add(
                TutorialPage(
                    title = "A game's page",
                    body = "Press ${key(GamepadAction.Y)} on a game to open its page: Play, which program " +
                        "runs it, and anything it still needs. The first Windows game needs one download " +
                        "of the Windows tools; its page offers it, and after that it says Play.",
                ),
            )
            add(
                TutorialPage(
                    title = "The Quick Menu",
                    body = "Press ${key(GamepadAction.R2)}, hold ${key(GamepadAction.SELECT)}, or tap " +
                        "${key(GamepadAction.R2)} in the top corner. Notifications has your notifications " +
                        "(it asks for access the first time). System has Wi-Fi, volume, brightness, " +
                        "Bluetooth and Switch mode.",
                ),
            )
        }
        add(
            TutorialPage(
                title = "Switching modes",
                body = "Switch mode opens a short list: the Android home screen, the modes that are on, " +
                    "and Modes and settings, where modes are turned on and off.",
                rows = buildList {
                    if (gaming) add(TutorialRow("In Gaming", "Quick Menu, then System, then Switch mode"))
                    if (desktop) add(TutorialRow("In Desktop", "The Modes button on the taskbar"))
                    when (home) {
                        HomeRolePrefs.HomeImplementation.STANDARD -> {
                            if (gaming || desktop) {
                                add(TutorialRow("On the home screen", "The droidtop icon opens " + opensLabel(gaming, desktop)))
                            }
                            add(TutorialRow("On the home screen", "Touch and hold an empty spot, then droidtop modes"))
                        }
                        else -> if (gaming || desktop) {
                            add(TutorialRow("From your launcher", "The droidtop icon opens " + opensLabel(gaming, desktop)))
                        }
                    }
                    add(TutorialRow("Anywhere", "Hold Back, on devices whose Back button can be held"))
                    if (home != HomeRolePrefs.HomeImplementation.NONE) {
                        add(
                            TutorialRow(
                                "Home button",
                                when {
                                    !defaultChosen -> "Takes you back to the mode you used last"
                                    homeGoesTo == null || homeGoesTo == Mode.LAUNCHER -> "Takes you to your Android home screen"
                                    else -> "Takes you to ${homeGoesTo.label}"
                                },
                            ),
                        )
                    }
                },
            ),
        )
        add(
            TutorialPage(
                title = "Settings",
                body = if (gaming) {
                    "Settings is the third section in Gaming. It has your game folders, the Scraper " +
                        "(pictures and descriptions for your games), the theme and Browse themes, and " +
                        "your controller. Global settings, its first row, has the modes, your home " +
                        "screen, setup from the start and this tutorial."
                } else {
                    "Modes and settings, in the mode switcher, opens Global settings: the modes, your " +
                        "home screen, setup from the start and this tutorial."
                },
            ),
        )
        add(
            TutorialPage(
                title = "Getting help",
                body = "The bar along the bottom of each screen always says what the buttons do right " +
                    "there." + (if (gaming) " ${key(GamepadAction.Y)} on a game shows everything droidtop knows about it." else "") +
                    " To see this again, open Global settings and choose Show the tutorial.",
            ),
        )
    }

    private fun opensLabel(gaming: Boolean, desktop: Boolean): String = when {
        gaming && desktop -> "Gaming or Desktop, whichever you use"
        gaming -> "Gaming"
        else -> "Desktop"
    }
}

@Composable
private fun TutorialScreen(pages: List<TutorialPage>, onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val last = index >= pages.lastIndex
    val window = currentShellWindow()
    val nextFocus = remember { FocusRequester() }
    fun back() {
        if (index > 0) index-- else onDone()
    }
    fun next() {
        if (last) onDone() else index++
    }
    BackHandler { back() }
    // The pad's selection starts on the way on, page after page.
    LaunchedEffect(index) { runCatching { nextFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MenuTokens.Ground)
            .systemBarsPadding()
            .ownPadButtons(::back),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = window.edgePadding),
        ) {
            Text(
                "Tutorial: ${index + 1} of ${pages.size}",
                color = MenuTokens.OnSurfaceMuted,
                style = TypeRole.sectionLabel,
                modifier = Modifier.padding(top = Space.Lg, bottom = Space.Md),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = Space.Lg, bottom = Space.Lg),
                verticalArrangement = Arrangement.spacedBy(Space.Md),
            ) {
                Text(page.title, color = MenuTokens.OnSurface, style = TypeRole.screenTitle)
                Text(
                    page.body,
                    color = MenuTokens.OnSurfaceMuted,
                    style = TypeRole.body,
                    modifier = Modifier.widthIn(max = Measure.bodyMaxWidth),
                )
                page.rows.forEach { row -> TutorialRowView(row) }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = Space.Lg),
                horizontalArrangement = Arrangement.spacedBy(Space.Md, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!last) PadButton("Skip the tutorial", onDone)
                Box(Modifier.weight(1f))
                if (index > 0) PadButton("Back", ::back)
                PadButton(
                    if (last) "Done" else "Next",
                    ::next,
                    filled = true,
                    modifier = Modifier.focusRequester(nextFocus),
                )
            }
        }
        TouchHintBar(
            hints = listOf(
                GamepadAction.A to "Select",
                GamepadAction.B to "Back",
            ),
        )
    }
}

@Composable
private fun TutorialRowView(row: TutorialRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = currentShellWindow().minTouchTarget)
            .background(MenuTokens.Surface, MenuTokens.RowShape)
            .padding(horizontal = Space.Lg, vertical = Space.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
    ) {
        // The same pill the hint row draws for a button, so the tutorial
        // and the screens it describes name a button the same way.
        Text(
            row.key,
            color = MenuTokens.OnSelected,
            style = TypeRole.supporting,
            modifier = Modifier
                .background(MenuTokens.Selected, RoundedCornerShape(50))
                .padding(horizontal = Space.Sm, vertical = Space.Hair),
        )
        Text(row.does, color = MenuTokens.OnSurface, style = TypeRole.supporting)
    }
}
