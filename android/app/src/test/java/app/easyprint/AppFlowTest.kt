package app.easyprint

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app (MainActivity + AppViewModel + Compose UI) like a person:
 * share a PDF to it, add the printer by IP, pick "Flip on long edge", print,
 * follow the two-sided guide. Also audits accessibility of every tappable control.
 * Needs $EVE_PORT (reference IPP printer), $EVE_PDF and $PDFIUM_LIB.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", shadows = [ShadowPdfiumRenderer::class, ShadowPdfiumPage::class])
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 60_000, substring: Boolean = true) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun audit(where: String): List<String> {
        val problems = mutableListOf<String>()
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
        fun visit(n: SemanticsNode) {
            val c = n.config
            val clickable = c.getOrNull(SemanticsActions.OnClick) != null
            if (clickable) {
                val label = listOfNotNull(
                    c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
                    c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
                ).joinToString(" ").trim()
                val merged = rule.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes()
                    .firstOrNull { it.id == n.id }
                val mergedLabel = merged?.let {
                    listOfNotNull(
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
                        it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text },
                    ).joinToString(" ").trim()
                } ?: label
                if (mergedLabel.isBlank()) problems += "$where: unlabeled control at ${n.boundsInRoot}"
                // Use the control's real layout size (not its on-screen clip, which shrinks while scrolled),
                // plus Compose's minimum-touch-target expansion.
                val density = rule.density
                val minPx = with(density) { 48.dp.toPx() } - 1
                val tb = n.touchBoundsInRoot
                val w = maxOf(n.size.width.toFloat(), tb.width)
                val h = maxOf(n.size.height.toFloat(), tb.height)
                if (n.size.width > 0 && n.size.height > 0 && (w < minPx || h < minPx))
                    problems += "$where: small touch target '${mergedLabel.take(30)}' ${with(density) { w.toDp() }}×${with(density) { h.toDp() }}"
            }
            n.children.forEach { visit(it) }
        }
        roots.forEach { visit(it) }
        return problems
    }

    @Test fun shareAndPrintTwoSided() {
        // A Canon G3000 (or tools/printsim/fake_g3000.py) at $CANON_HOST, and a PDF to share.
        val host = System.getenv("CANON_HOST")
        val pdf = System.getenv("CANON_PDF")?.let { File(it) }
        assumeTrue(host != null && pdf != null && Pdfium.available)
        val problems = mutableListOf<String>()

        // Empty state
        waitForText("What would you like to print?")
        problems += audit("empty screen")

        // "Share → Easy Print" from another app
        rule.runOnUiThread {
            rule.activity.handleIntent(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pdf))
            })
        }
        waitForText("3 pages")
        println("✓ shared PDF opened: ${pdf!!.name}")

        // No Wi-Fi in the test machine, so the automatic search finds nothing: add the printer by IP.
        waitForText("Set up")
        rule.onNodeWithText("Set up").performClick()
        rule.onNodeWithText("Add a printer by IP address").performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextInput(host!!)
        rule.onNodeWithText("Add printer").performClick()
        waitForText("Added Canon G3000 series")
        println("✓ printer added by IP and identified over SNMP as 'Canon G3000 series'")
        rule.waitUntil(20_000) { rule.onAllNodesWithText("Ready", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        problems += audit("printer sheet")
        rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        println("✓ printer status shows Ready")

        // Choose "Flip on long edge" and print
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Flip on long edge"))
        rule.onNodeWithText("Flip on long edge").performClick()
        waitForText("prints in two rounds")
        problems += audit("main screen")
        rule.onNodeWithText("Print", useUnmergedTree = true).performClick()

        waitForText("Printing the fronts")
        println("✓ wizard step 1 shown")
        waitForText("Wait until all", timeoutMs = 120_000) // fronts rendered at 600 dpi and sent over port 9100
        println("✓ fronts sent to the printer")
        problems += audit("wizard step 1")
        rule.onNodeWithText("All fronts are out").performClick()

        waitForText("Put the pile back in")
        waitForText("Take the top sheet off the pile")  // 3 pages: last sheet has no back
        println("✓ step 2 tells the person to set the top sheet aside (odd page count)")
        problems += audit("wizard step 2")
        rule.onNodeWithText("Print the backs").performClick()

        waitForText("All done!", timeoutMs = 90_000)
        println("✓ backs sent, 'All done!' shown")
        rule.onNodeWithText("Done").performClick()

        // Recent prints remembers it
        rule.onNodeWithContentDescription("Recent prints").performClick()
        waitForText("Use again")
        println("✓ job appears in Recent prints")

        println("Accessibility audit: ${if (problems.isEmpty()) "no problems" else problems.distinct().joinToString("\n  ", "\n  ")}")
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }
}
