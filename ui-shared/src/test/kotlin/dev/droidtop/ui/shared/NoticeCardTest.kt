package dev.droidtop.ui.shared

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeAction
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card's accessibility (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485, final design test 6): one
 * node, tone first, polite for a group notice or a banner and not for a row, with Info and the action
 * as custom actions that do what they say.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a banner is one polite node with Info and its action`() {
        var acted = 0
        var info = 0
        val notice = Notice(
            NoticeTone.Caution,
            "Unofficial plugin",
            body = "Approve it only if someone you trust pointed you to it.",
            details = "Source: example",
            action = NoticeAction("Approve") {},
        )
        rule.setContent {
            NoticeCard(notice, NoticePlacement.Page, onAction = { acted++ }, onInfo = { info++ })
        }
        val node = rule.onNodeWithContentDescription(notice.spoken()).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Info", "Approve"), actions.map { it.label })
        actions.forEach { it.action() }
        assertEquals(1, acted)
        assertEquals(1, info)
    }

    @Test
    fun `a row is not a live region`() {
        val notice = Notice(NoticeTone.Info, "Downloads open in your browser")
        rule.setContent { NoticeCard(notice, NoticePlacement.Row) }
        val node = rule.onNodeWithContentDescription(notice.spoken()).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertNull(node.config.getOrNull(SemanticsActions.CustomActions))
    }
}
