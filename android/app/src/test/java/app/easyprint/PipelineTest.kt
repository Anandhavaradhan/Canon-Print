package app.easyprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.easyprint.android.*
import app.easyprint.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer

/** End-to-end on the JVM: document -> plan -> rendered bands -> PWG raster (-> test IPP server). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PipelineTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun photo(): OpenedFile {
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 300f, 200f, Paint().apply { color = Color.RED }) // top-left quadrant red
        val f = File(ctx.cacheDir, "photo.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Documents.fromFile("p1", "photo.png", f, DocKind.IMAGE)
    }

    /** Decode first page of a PWG file -> (w, h, bytesPerPixel, pixels) */
    private fun decodeFirst(file: File): Pair<IntArray, IntArray> {
        val din = DataInputStream(file.inputStream().buffered())
        val sync = ByteArray(4); din.readFully(sync); assertEquals("RaS2", String(sync))
        val h = ByteArray(1796); din.readFully(h)
        fun u(o: Int) = ByteBuffer.wrap(h, o, 4).int
        val w = u(372); val ht = u(376); val bpp = u(388) / 8
        val px = IntArray(w * ht)
        var row = 0
        while (row < ht) {
            val rep = din.readUnsignedByte() + 1
            val line = ByteArray(w * bpp); var x = 0
            while (x < w) {
                val c = din.readUnsignedByte()
                if (c <= 127) { val one = ByteArray(bpp); din.readFully(one); repeat(c + 1) { System.arraycopy(one, 0, line, x * bpp, bpp); x++ } }
                else { val n = 257 - c; din.readFully(line, x * bpp, n * bpp); x += n }
            }
            repeat(rep) {
                for (i in 0 until w) {
                    val r = line[i * bpp].toInt() and 0xff
                    val g = if (bpp == 3) line[i * bpp + 1].toInt() and 0xff else r
                    val b = if (bpp == 3) line[i * bpp + 2].toInt() and 0xff else r
                    px[row * w + i] = (r shl 16) or (g shl 8) or b
                }
                row++
            }
        }
        return intArrayOf(w, ht, bpp) to px
    }

    @Test fun imageToRaster() {
        val doc = photo()
        val r = DocRenderer(listOf(doc))
        val plan = makePlan(r.pageSizes, PrintSettings(paper = Paper.PHOTO_4X6, marginMm = 0f))
        assertTrue("landscape photo -> landscape side", plan.sheets[0].front.landscape)
        val jobs = buildJobs(plan)
        val out = File(ctx.cacheDir, "job.pwg")
        DirectOutput.writeRaster(r, plan, jobs.passes[0], null, plan.settings, out) { _, _ -> }
        val (dims, px) = decodeFirst(out)
        val (w, h, bpp) = Triple(dims[0], dims[1], dims[2])
        assertEquals(3, bpp)
        assertTrue("portrait physical page", h > w)
        // The landscape photo is turned anticlockwise: its top-left (red) lands bottom-left.
        fun at(fx: Float, fy: Float) = px[(fy * h).toInt() * w + (fx * w).toInt()]
        val bottomLeft = at(0.25f, 0.75f); val topLeft = at(0.25f, 0.25f)
        println("bottomLeft=%06x topLeft=%06x".format(bottomLeft, topLeft))
        assertTrue("red at bottom-left", (bottomLeft shr 16) > 200 && (bottomLeft and 0xff) < 60)
        assertTrue("white at top-left", (topLeft and 0xff) > 200)
        System.getenv("IPP_TEST_PORT")?.toIntOrNull()?.let { port ->
            val id = IppClient("127.0.0.1", port, "ipp/print").printJob(out, DirectOutput.jobOptions(null, plan.settings, jobs.passes[0], "Photo"))
            println("sent photo, job-id=$id, ${out.length()} bytes")
            assertTrue(id > 0)
        }
        r.close()
    }

    @Test fun pdfPipeline() {
        val f = File(ctx.cacheDir, "cal.pdf")
        val ok = runCatching { Documents.calibrationPdf(f) }.isSuccess && f.length() > 0
        assumeTrue("PdfDocument not available in this Robolectric build", ok)
        val doc = runCatching { Documents.fromFile("c", "cal.pdf", f, DocKind.PDF) }.getOrNull()
        assumeTrue("PdfRenderer not available in this Robolectric build", doc != null)
        assertEquals(4, doc!!.pages.size)
        val r = DocRenderer(listOf(doc))
        val plan = makePlan(r.pageSizes, PrintSettings(sides = Sides.LONG, color = ColorMode.BW))
        val jobs = buildJobs(plan)
        assertEquals(2, jobs.passes.size)
        val out = File(ctx.cacheDir, "cal.pwg")
        DirectOutput.writeRaster(r, plan, jobs.passes[1], null, plan.settings, out) { _, _ -> }
        val (dims, _) = decodeFirst(out)
        assertEquals(1, dims[2]) // gray
        System.getenv("IPP_TEST_PORT")?.toIntOrNull()?.let { port ->
            val id = IppClient("127.0.0.1", port, "ipp/print").printJob(out, DirectOutput.jobOptions(null, plan.settings, jobs.passes[1], "Backs"))
            println("sent backs, job-id=$id, ${out.length()} bytes")
            assertTrue(id > 0)
        }
        r.close()
    }
}
