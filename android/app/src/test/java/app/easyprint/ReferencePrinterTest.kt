package app.easyprint

import app.easyprint.android.*
import app.easyprint.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Talks to CUPS's reference IPP Everywhere printer (ippeveprinter) on $EVE_PORT using the
 * app's real pipeline: capabilities -> raster -> Print-Job -> poll job state -> backs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ShadowPdfiumRenderer::class, ShadowPdfiumPage::class])
class ReferencePrinterTest {
    @Test fun twoSidedJobAgainstReferencePrinter() {
        val port = System.getenv("EVE_PORT")?.toIntOrNull()
        val pdf = System.getenv("EVE_PDF")?.let { File(it) }
        assumeTrue(port != null && pdf != null && Pdfium.available)
        val printer = PrinterChoice.Direct("Reference", "127.0.0.1", port!!, "ipp/print")
        val client = printer.client()
        val info = client.getPrinterAttributes()
        println("printer: ${info.makeAndModel} state=${info.state} formats=${info.formats} res=${info.resolutions} types=${info.rasterTypes} media=${info.media.take(3)} quality=${info.qualities}")
        println("status line the app would show: ${describePrinterState(info)}")
        assertTrue(info.supportsPwg)

        val doc = Documents.fromFile("d", pdf!!.name, pdf, DocKind.PDF)
        val r = DocRenderer(listOf(doc))
        for (color in listOf(ColorMode.COLOR, ColorMode.BW)) {
            val plan = makePlan(r.pageSizes, PrintSettings(sides = Sides.LONG, color = color, quality = Quality.STANDARD, paperType = PaperType.PHOTO_GLOSSY))
            val jobs = buildJobs(plan)
            jobs.passes.forEachIndexed { i, pass ->
                val f = File.createTempFile("pass$i", ".pwg")
                DirectOutput.writeRaster(r, plan, pass, info, plan.settings, f) { _, _ -> }
                val opts = DirectOutput.jobOptions(info, plan.settings, pass, "Test ${color.name} ${pass.label}")
                val id = client.printJob(f, opts)
                println("${color.name} ${pass.label}: job $id, ${f.length()} bytes, status 0x%04x, ignored=%s".format(client.lastStatus, client.lastUnsupported.keys))
                assertTrue(id > 0)
                // How the app knows the fronts have finished:
                var state = 0
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 30_000) {
                    state = client.jobState(id).first
                    if (state >= 7) break
                    Thread.sleep(300)
                }
                println("   job $id final state $state after ${System.currentTimeMillis() - t0} ms")
                assertEquals("job should complete", 9, state)
            }
        }
        r.close()
    }
}
