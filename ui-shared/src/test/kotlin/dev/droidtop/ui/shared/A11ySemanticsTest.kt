package dev.droidtop.ui.shared

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.ButtonBarItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page pieces' accessibility (docs/SPEC.md 7k, "Page anatomy"; Droidtop/tracker#485 F2a, final design
 * C.8): every pressable thing has a label and a role and is at least 48dp tall, a disabled button still says
 * why in its label, the header is a heading, and the focus line says where the rest of a cut sentence is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Real text layout, so a sentence too long for three lines really overflows.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A11ySemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private val bar = ButtonBarItem(
        buttons = listOf(
            PageButton("open", "Open", ButtonRole.PRIMARY) {},
            PageButton("update", "Update", enabled = false, disabledReason = "Needs Wi-Fi") {},
        ),
        more = listOf(PageButton("remove", "Uninstall", ButtonRole.DANGER, confirmTitle = "Uninstall F95zone?") {}),
    )

    @Test
    fun `every button is a labelled 48dp button, and a disabled one says why`() {
        var pressed = -1
        rule.setContent { ButtonBarView(bar, selected = 0, onPress = { pressed = it }) }
        listOf("Open", "Needs Wi-Fi", ButtonBarItem.MORE_LABEL).forEach { label ->
            val node = rule.onNodeWithContentDescription(label)
            node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(120.dp)
            assertEquals(Role.Button, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithContentDescription("Open").assertWidthIsAtLeast(160.dp)
        assertNotNull(rule.onNodeWithContentDescription("Needs Wi-Fi").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled))
        assertEquals(true, rule.onNodeWithContentDescription("Open").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        rule.onNodeWithContentDescription(ButtonBarItem.MORE_LABEL).performClick()
        assertEquals(2, pressed)
    }

    @Test
    fun `the header is one heading node`() {
        rule.setContent { PageHeaderView(PageHeader("F95zone", "gamegrab-sources · v0.3.1", listOf(CatalogChip("Unofficial")))) }
        // The header merges its title, byline and chips into one node, and that node is the heading.
        val node = rule.onNodeWithText("F95zone").fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.Heading))
    }

    @Test
    fun `a cut focus line says where the rest is`() {
        val long = List(40) { "Lets it add games to your library." }.joinToString(" ")
        rule.setContent { FocusLine(long) }
        rule.onNodeWithText("Y Info", substring = true).assertExists()
    }

    @Test
    fun `an empty state has a next action`() {
        var started = false
        rule.setContent { StateBlock(StateKind.EMPTY, "No plugins yet", body = "Add game stores, emulator controls and more.", primary = "Open Store" to { started = true }) }
        rule.onNodeWithContentDescription("Open Store").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(started)
    }

    @Test
    fun `a page bar refuses more than three buttons, two primaries and an unconfirmed danger`() {
        val one = PageButton("a", "A") {}
        assertTrue(runCatching { ButtonBarItem(listOf(one, one, one, one)) }.isFailure)
        assertTrue(runCatching { ButtonBarItem(listOf(PageButton("a", "A", ButtonRole.PRIMARY) {}, PageButton("b", "B", ButtonRole.PRIMARY) {})) }.isFailure)
        assertTrue(runCatching { ButtonBarItem(listOf(PageButton("d", "Delete", ButtonRole.DANGER) {})) }.isFailure)
        assertEquals(1, ButtonBarItem(listOf(one, PageButton("p", "Install", ButtonRole.PRIMARY) {})).initialSlot())
    }
}
