package app.easyprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.easyprint.android.*
import app.easyprint.core.*
import app.easyprint.ui.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real UI to PNG files for review (set SCREENSHOT_DIR).
 * Uses fake page images; no printer or files needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2900dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val outDir = System.getenv("SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }

    private fun fakePage(n: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); textSize = h * 0.25f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText("$n", w / 2f, h * 0.55f, p)
        val line = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = h * 0.012f }
        for (i in 0 until 6) c.drawLine(w * 0.15f, h * (0.68f + i * 0.04f), w * if (i == 5) 0.6f else 0.85f, h * (0.68f + i * 0.04f), line)
        p.textSize = h * 0.05f; p.color = Color.rgb(43, 85, 214)
        c.drawText("Chapter $n", w / 2f, h * 0.14f, p)
        return b
    }

    private val loader: PreviewLoader = { plan, sheet, back, widthPx ->
        val sh = plan.sheets[sheet]
        val side = if (back) sh.back else sh.front
        val land = (side ?: sh.front).landscape
        val ratio = if (land) plan.paperW / plan.paperH else plan.paperH / plan.paperW
        val h = (widthPx * ratio).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val k = widthPx / (if (land) plan.paperH else plan.paperW)
        side?.placements?.forEach { pl ->
            val pw = (595 * pl.scale * k).toInt().coerceAtLeast(1); val ph = (842 * pl.scale * k).toInt().coerceAtLeast(1)
            c.drawBitmap(fakePage(pl.src + 1, pw, ph), pl.x * k, pl.y * k, null)
            if (plan.borders) c.drawRect(pl.cell.x * k, pl.cell.y * k, (pl.cell.x + pl.cell.w) * k, (pl.cell.y + pl.cell.h) * k,
                Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY })
        }
        bmp
    }

    private fun state(settings: PrintSettings, files: Int = 1, look: LookPrefs = LookPrefs()): UiState {
        val f = (1..files).map { i ->
            OpenedFile("f$i", if (i == 1) "Class notes.pdf" else "Worksheet.pdf", DocKind.PDF, File("x"), List(if (i == 1) 7 else 2) { PageSize(595f, 842f) })
        }
        val sizes = f.flatMap { it.pages }
        val plan = makePlan(sizes, settings)
        val canon = PrinterChoice.Direct("Canon G3010 series", "192.168.1.23")
        return UiState(
            files = f, settings = settings, plan = plan, printers = listOf(canon, PrinterChoice.System), printer = canon,
            status = PrinterStatus("ready", "Ready", listOf(
                PrinterInfo.Marker("Black", "#000000", 80, "ink-cartridge"), PrinterInfo.Marker("Cyan", "#00A0E0", 65, "ink-cartridge"),
                PrinterInfo.Marker("Magenta", "#E4007F", 70, "ink-cartridge"), PrinterInfo.Marker("Yellow", "#FFD800", 45, "ink-cartridge"),
            )),
            look = look, searching = false,
        )
    }

    private val noop = object : Actions {
        override fun pickFiles() {}
        override fun removeFile(f: OpenedFile) {}
        override fun moveFile(index: Int, delta: Int) {}
        override fun settings(t: (PrintSettings) -> PrintSettings) {}
        override fun applySaved(p: SavedPreset) {}
        override fun deletePreset(p: SavedPreset) {}
        override fun savePreset(name: String) {}
        override fun print() {}
        override fun openPrinters() {}
        override fun openDisplay() {}
        override fun openHistory() {}
        override fun openHelp() {}
        override fun runTest() {}
        override fun reset() {}
    }

    private fun shot(name: String, dialog: Boolean = false, content: @Composable () -> Unit) {
        assumeTrue(outDir != null)
        rule.setContent(content)
        rule.waitForIdle()
        Thread.sleep(300); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val root = rule.activity.window.decorView
        val img = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(img))
        File(outDir, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun main_light() = shot("main_light") {
        val s = state(PrintSettings(sides = Sides.LONG, pagesPerSide = 2, color = ColorMode.BW))
        EasyPrintTheme(s.look) { MainScreen(s, noop, loader, SnackbarHostState()) }
    }

    @Test fun main_dark_large() = shot("main_dark_large") {
        val s = state(PrintSettings(booklet = true), files = 2, look = LookPrefs(textScale = 1.3f, theme = ThemeMode.DARK))
        EasyPrintTheme(s.look) { MainScreen(s, noop, loader, SnackbarHostState()) }
    }

    @Test fun main_contrast() = shot("main_contrast") {
        val s = state(PrintSettings(), look = LookPrefs(theme = ThemeMode.CONTRAST))
        EasyPrintTheme(s.look) { MainScreen(s, noop, loader, SnackbarHostState()) }
    }

    @Test fun empty() = shot("empty") {
        val s = state(PrintSettings()).copy(files = emptyList(), plan = null)
        EasyPrintTheme(s.look) { MainScreen(s, noop, loader, SnackbarHostState()) }
    }

    @Test fun wizard_turn() = shot("wizard_turn") {
        EasyPrintTheme(LookPrefs()) {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.bg), contentAlignment = Alignment.TopCenter) {
                WizardContent(Wizard(WizardStep.TURN, sheets = 4, removeTopSheet = true, twoPass = true), true, null, {}, {}, {}, {}, { _, _ -> })
            }
        }
    }

    @Test fun wizard_fronts() = shot("wizard_fronts") {
        EasyPrintTheme(LookPrefs()) {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.bg), contentAlignment = Alignment.TopCenter) {
                WizardContent(Wizard(WizardStep.FRONTS, sheets = 4, twoPass = true, frontsDone = true), true, null, {}, {}, {}, {}, { _, _ -> })
            }
        }
    }
}
