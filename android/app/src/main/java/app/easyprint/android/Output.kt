package app.easyprint.android

import android.graphics.RectF
import app.easyprint.core.CanonG3000
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
import java.net.InetSocketAddress
import java.net.Socket
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

    /**
     * A Canon printer driven the way Canon's own software does it: found over SNMP,
     * jobs sent as IVEC + PWG Raster over TCP port 9100. This is the path for the
     * G3000, which has no IPP (so Android printing and Mopria can't see it).
     */
    data class Canon(
        val name: String,
        val host: String,
        val mac: String = "",
        val manual: Boolean = false,
        val port: Int = CanonG3000.PORT_RAW,
    ) : PrinterChoice() {
        override val key get() = "canon://$host:$port"
        override val label get() = name
    }
}

fun PrinterChoice.host() = when (this) {
    is PrinterChoice.Canon -> host
    is PrinterChoice.Direct -> host
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
        val types = info?.rasterTypes.orEmpty()
        val grayType = types.isEmpty() || "sgray_8" in types
        val rgbType = types.isEmpty() || "srgb_8" in types
        // Black & white, or a printer that only takes greyscale raster
        val gray = (bw || !rgbType) && grayType
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
                    if ((bw || !rgbType) && !gray) toGrayInPlace(row, w * rows)
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
        colorMode = run {
            val modes = info?.colorModes.orEmpty()
            val wantBw = settings.color == ColorMode.BW || (modes.isNotEmpty() && "color" !in modes)
            when {
                !wantBw -> "color"
                modes.isEmpty() || "monochrome" in modes -> "monochrome"
                else -> modes.first()
            }
        },
        quality = settings.quality.ippValue,
        sides = when (pass.sides) {
            Sides.ONE -> "one-sided"
            Sides.LONG -> "two-sided-long-edge"
            Sides.SHORT -> "two-sided-short-edge"
        },
    )
}

object CanonOutput {
    /**
     * Render one pass and write the complete Canon job (IVEC blocks + one PWG Raster
     * stream per page) to [out]. Each page covers only the G3000's imageable area,
     * at 600 dpi, 24-bit sRGB, exactly like Canon's driver.
     */
    fun writeJob(
        renderer: DocRenderer, plan: Plan, pass: Pass, settings: PrintSettings,
        title: String, jobId: Int, out: File, progress: (Int, Int) -> Unit,
    ) {
        val m = CanonG3000.media(settings.paper)
        val bw = settings.color == ColorMode.BW
        val region = RectF(m.originX, m.originY, m.originX + (m.urx - m.llx), m.originY + (m.ury - m.lly))
        val w = m.widthPx
        val pageFiles = mutableListOf<File>()
        try {
            pass.pages.forEachIndexed { i, page ->
                progress(i + 1, pass.pages.size)
                val f = File(out.parentFile, "${out.name}.p$i.pwg")
                pageFiles += f
                f.outputStream().buffered(256 * 1024).use { os ->
                    val writer = PwgRasterWriter(os) // one complete stream per page, as Canon sends
                    writer.beginPage(
                        PwgRasterWriter.PageHeader(
                            widthPx = w, heightPx = m.heightPx, dpi = CanonG3000.DPI, gray = false,
                            pageSizePt = Math.round(m.w) to Math.round(m.h),
                            pageSizeName = m.ivecName, mediaType = settings.paperType.canonCode,
                            // Standard = 0 ("printer default"), exactly what Canon's driver sends; only
                            // Draft/High are signalled, via the PWG header's PrintQuality field.
                            printQuality = if (settings.quality == Quality.STANDARD) 0 else settings.quality.ippValue,
                            totalPages = 0,
                        )
                    )
                    val row = IntArray(w * 256)
                    renderPhysicalBands(renderer, plan, page.side, page.rotate180, CanonG3000.DPI, region = region) { band, _, rows ->
                        band.getPixels(row, 0, w, 0, 0, w, rows)
                        if (bw) toGray(row, w * rows) // Canon sends 24-bit sRGB even in monochrome
                        writer.writeRows(row, w, rows)
                    }
                    writer.endPage()
                }
            }
            val job = CanonG3000.JobInfo(
                jobId = jobId, title = title, user = "Easy Print", paper = settings.paper,
                paperType = settings.paperType, monochrome = bw,
            )
            out.outputStream().buffered(256 * 1024).use { CanonG3000.writeJob(it, job, pageFiles) }
        } finally {
            pageFiles.forEach { it.delete() }
        }
    }

    private fun toGray(px: IntArray, n: Int) {
        for (i in 0 until n) {
            val p = px[i]
            val g = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
            px[i] = (0xff shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** Send a prepared job to the printer's raw port (one-way stream, like Canon's driver). */
    fun send(host: String, port: Int, job: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        // Canon's driver first "taps" the printer over SNMP: a sleeping printer may not answer 9100 otherwise.
        runCatching { app.easyprint.core.Snmp.get(host, CanonG3000.COMMUNITY_CANON, listOf(CanonG3000.OID_MODEL), 1500, retries = 0) }
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 8000)
            s.soTimeout = 60_000
            s.tcpNoDelay = false
            val os = s.getOutputStream()
            val total = job.length()
            var sent = 0L
            job.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    sent += n
                    progress(sent, total)
                }
            }
            os.flush()
            s.shutdownOutput()
            // Port 9100 is one-way: give the printer a moment to drain, but never wait long.
            s.soTimeout = 3000
            runCatching { s.getInputStream().read() }
        }
    }

    /** Is something listening on the printer's raw port? */
    fun reachable(host: String, port: Int, timeoutMs: Int = 2500): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }; true
    }.getOrDefault(false)
}
