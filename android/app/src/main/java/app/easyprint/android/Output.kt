package app.easyprint.android

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import app.easyprint.core.ColorMode
import app.easyprint.core.IppClient
import app.easyprint.core.Paper
import app.easyprint.core.Pass
import app.easyprint.core.Plan
import app.easyprint.core.PrintSettings
import app.easyprint.core.PrinterInfo
import app.easyprint.core.PwgRasterWriter
import app.easyprint.core.Quality
import app.easyprint.core.Sides
import java.io.File
import kotlin.math.abs

/** Where a job goes. */
sealed class PrinterChoice {
    abstract val key: String
    abstract val label: String

    /** A printer on the Wi-Fi network, printed to directly over IPP. */
    data class Direct(
        val name: String,
        val host: String,
        val port: Int = 631,
        val path: String = "ipp/print",
        val manual: Boolean = false,
    ) : PrinterChoice() {
        override val key get() = "ipp://$host:$port/$path"
        override val label get() = name
        fun client() = IppClient(host, port, path)
    }

    /** Android's own printing (Canon Print Service, Mopria, Save as PDF, any printer). */
    data object System : PrinterChoice() {
        override val key = "system"
        override val label = "Other printers & Save as PDF"
    }
}

fun isCanonG(name: String) = name.lowercase().replace(" ", "").let { it.contains("canon") && Regex("g3\\d{3}|g30\\d0").containsMatchIn(it) }

object DirectOutput {
    fun chooseDpi(info: PrinterInfo?, q: Quality): Int {
        val res = info?.resolutions.orEmpty().filter { it in 75..1200 }
        if (res.isEmpty()) return if (q == Quality.HIGH) 600 else 300
        return when (q) {
            Quality.DRAFT -> res.firstOrNull { it >= 150 } ?: res.first()
            Quality.STANDARD -> res.minBy { abs(it - 300) }
            Quality.HIGH -> res.filter { it <= 600 }.maxOrNull() ?: res.first()
        }
    }

    /** A media keyword the printer knows, matching the paper size. */
    fun mediaKeyword(info: PrinterInfo?, paper: Paper): String {
        val known = info?.media.orEmpty()
        if (paper.pwgName in known) return paper.pwgName
        val wantW = paper.widthPt / 72f * 25.4f
        val wantH = paper.heightPt / 72f * 25.4f
        for (m in known) {
            val dims = Regex("""_(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)(mm|in)$""").find(m) ?: continue
            val f = if (dims.groupValues[3] == "in") 25.4f else 1f
            val w = dims.groupValues[1].toFloat() * f
            val h = dims.groupValues[2].toFloat() * f
            if (abs(w - wantW) < 2.5f && abs(h - wantH) < 2.5f) return m
        }
        return paper.pwgName
    }

    /**
     * Write one pass as a PWG raster file. Returns the number of pages.
     * [progress] gets (page, total).
     */
    fun writeRaster(
        renderer: DocRenderer, plan: Plan, pass: Pass, info: PrinterInfo?, settings: PrintSettings,
        out: File, progress: (Int, Int) -> Unit,
    ) {
        val dpi = chooseDpi(info, settings.quality)
        val bw = settings.color == ColorMode.BW
        val grayType = info == null || info.rasterTypes.isEmpty() || "sgray_8" in info.rasterTypes
        val gray = bw && grayType
        val (w, h) = physicalSizePx(plan, dpi)
        val media = mediaKeyword(info, settings.paper)
        out.outputStream().buffered(256 * 1024).use { os ->
            val writer = PwgRasterWriter(os)
            val total = pass.pages.size
            pass.pages.forEachIndexed { i, page ->
                progress(i + 1, total)
                writer.beginPage(
                    PwgRasterWriter.PageHeader(
                        widthPx = w, heightPx = h, dpi = dpi, gray = gray,
                        pageSizePt = Math.round(plan.paperW) to Math.round(plan.paperH),
                        pageSizeName = media, mediaType = settings.paperType.ippMediaType,
                        printQuality = settings.quality.ippValue, totalPages = total,
                        duplex = pass.sides != Sides.ONE, tumble = pass.sides == Sides.SHORT,
                    )
                )
                val row = IntArray(w * 256)
                renderPhysicalBands(renderer, plan, page.side, page.rotate180, dpi) { band, _, rows ->
                    band.getPixels(row, 0, w, 0, 0, w, rows)
                    if (bw && !gray) toGrayInPlace(row, w * rows)
                    writer.writeRows(row, w, rows)
                }
                writer.endPage()
            }
        }
    }

    private fun toGrayInPlace(px: IntArray, n: Int) {
        for (i in 0 until n) {
            val p = px[i]
            val g = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
            px[i] = (0xff shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    fun jobOptions(info: PrinterInfo?, settings: PrintSettings, pass: Pass, jobName: String) = IppClient.JobOptions(
        jobName = jobName,
        media = mediaKeyword(info, settings.paper),
        mediaType = settings.paperType.ippMediaType.takeIf { info?.mediaTypes.isNullOrEmpty() || it in info!!.mediaTypes },
        colorMode = if (settings.color == ColorMode.BW && (info?.colorModes.isNullOrEmpty() || "monochrome" in info!!.colorModes)) "monochrome"
        else if (settings.color == ColorMode.BW) "auto-monochrome" else "color",
        quality = settings.quality.ippValue,
        sides = when (pass.sides) {
            Sides.ONE -> "one-sided"
            Sides.LONG -> "two-sided-long-edge"
            Sides.SHORT -> "two-sided-short-edge"
        },
    )
}

object SystemOutput {
    private fun dpi(q: Quality) = when (q) { Quality.DRAFT -> 150; Quality.STANDARD -> 200; Quality.HIGH -> 300 }

    /** Render a pass into a PDF of portrait pages (images), for Android's print framework. */
    fun writePdf(renderer: DocRenderer, plan: Plan, pass: Pass, settings: PrintSettings, out: File, progress: (Int, Int) -> Unit) {
        val dpi = dpi(settings.quality)
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (settings.color == ColorMode.BW) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val k = 72f / dpi
        try {
            pass.pages.forEachIndexed { i, page ->
                progress(i + 1, pass.pages.size)
                val pdfPage = doc.startPage(
                    PdfDocument.PageInfo.Builder(Math.round(plan.paperW), Math.round(plan.paperH), i + 1).create()
                )
                val canvas = pdfPage.canvas
                renderPhysicalBands(renderer, plan, page.side, page.rotate180, dpi) { band: Bitmap, y0, rows ->
                    val src = android.graphics.Rect(0, 0, band.width, rows)
                    canvas.drawBitmap(band, src, RectF(0f, y0 * k, band.width * k, (y0 + rows) * k), paint)
                }
                doc.finishPage(pdfPage)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    fun mediaSize(p: Paper): PrintAttributes.MediaSize = when (p) {
        Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
        Paper.LETTER -> PrintAttributes.MediaSize.NA_LETTER
        Paper.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
        Paper.A5 -> PrintAttributes.MediaSize.ISO_A5
        Paper.B5 -> PrintAttributes.MediaSize.JIS_B5
        Paper.PHOTO_4X6 -> PrintAttributes.MediaSize.NA_INDEX_4X6
        Paper.PHOTO_5X7 -> PrintAttributes.MediaSize("na_5x7", "5×7 in", 5000, 7000)
        Paper.PHOTO_8X10 -> PrintAttributes.MediaSize("na_8x10", "8×10 in", 8000, 10000)
        Paper.ENV_DL -> PrintAttributes.MediaSize("iso_dl", "DL envelope", 4331, 8661)
        Paper.ENV_10 -> PrintAttributes.MediaSize("na_10", "#10 envelope", 4125, 9500)
    }

    /** Opens Android's print screen with our settings filled in. */
    fun print(activity: Activity, jobName: String, pdf: File, settings: PrintSettings, sides: Sides, pages: Int) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(mediaSize(settings.paper))
            .setColorMode(if (settings.color == ColorMode.BW) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setDuplexMode(
                when (sides) {
                    Sides.ONE -> PrintAttributes.DUPLEX_MODE_NONE
                    Sides.LONG -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                    Sides.SHORT -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                }
            )
            .build()
        pm.print(jobName, FileAdapter(jobName, pdf, pages), attrs)
    }

    private class FileAdapter(val name: String, val file: File, val pages: Int) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (signal?.isCanceled == true) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages)
                .build()
            cb.onLayoutFinished(info, old != new)
        }

        override fun onWrite(ranges: Array<out PageRange>, dest: ParcelFileDescriptor, signal: CancellationSignal?, cb: WriteResultCallback) {
            try {
                file.inputStream().use { input ->
                    java.io.FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) }
                }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(e.message)
            }
        }
    }
}
