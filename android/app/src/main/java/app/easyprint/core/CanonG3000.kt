package app.easyprint.core

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Canon's own print path for the PIXMA G3000 series (and other printers Canon's
 * "cnijfilter2" driver covers). These printers have no IPP, so Android printing,
 * Mopria and AirPrint can't find them. Canon's software instead:
 *
 *  - finds the printer with SNMP v1 (community "canon_admin", Canon enterprise OIDs),
 *  - sends the job as a raw TCP stream to port 9100,
 *  - wraps each page (standard PWG Raster, 600 dpi sRGB, imageable area only)
 *    in plain-text IVEC XML command blocks.
 *
 * Sources (all public): Canon cnijfilter2 6.90 source (canong3000.ppd,
 * tocanonij/include/cncl/cncl_paramtbl.h, cnclcmdutilsdef.h), the independent
 * reimplementations "canon-gm2080-macos" (MIT, confirmed printing on a Canon
 * MegaTank) and "cnijfilter2-loong64" (GPL, byte-compared with Canon's libraries).
 */
object CanonG3000 {
    const val DPI = 600 // canong3000.ppd offers only 600 dpi
    const val PORT_RAW = 9100

    // ---------------------------------------------------------------- SNMP
    const val COMMUNITY_CANON = "canon_admin"
    const val COMMUNITY_PUBLIC = "public"
    const val OID_MAC = "1.3.6.1.4.1.1602.1.3.1.13.0"
    const val OID_SERIAL = "1.3.6.1.4.1.1602.1.2.1.8.1.3.1.1"
    const val OID_MODEL = "1.3.6.1.4.1.1602.1.1.1.1.0"
    const val OID_CONN = "1.3.6.1.4.1.1602.1.3.1.12.0"
    const val OID_TYPE = "1.3.6.1.4.1.1602.1.1.1.10.0"
    val DISCOVERY_OIDS = listOf(OID_MAC, OID_SERIAL, OID_MODEL, OID_CONN, OID_TYPE)

    // Standard MIBs (community "public")
    const val OID_SYSDESCR = "1.3.6.1.2.1.1.1.0"
    const val OID_HR_PRINTER_STATUS = "1.3.6.1.2.1.25.3.5.1.1.1"
    const val OID_HR_ERROR_STATE = "1.3.6.1.2.1.25.3.5.1.2.1"
    const val OID_SUPPLY_DESC = "1.3.6.1.2.1.43.11.1.1.6.1"
    const val OID_SUPPLY_MAX = "1.3.6.1.2.1.43.11.1.1.8.1"
    const val OID_SUPPLY_LEVEL = "1.3.6.1.2.1.43.11.1.1.9.1"

    data class Found(val host: String, val model: String, val mac: String, val serial: String)

    /** Interpret a reply to the discovery request. A Canon printer always reports its MAC. */
    fun parseDiscovery(host: String, r: Snmp.Response?): Found? {
        r ?: return null
        val mac = (r.get(OID_MAC) as? Snmp.Value.Bytes)?.v?.takeIf { it.size >= 6 } ?: return null
        val model = (r.get(OID_MODEL) as? Snmp.Value.Bytes)?.text().orEmpty()
        val serial = (r.get(OID_SERIAL) as? Snmp.Value.Bytes)?.text().orEmpty()
        return Found(host, model.ifBlank { "Canon printer" }, mac.take(6).joinToString("") { "%02X".format(it) }, serial)
    }

    /** Plain-language status from hrPrinterStatus + hrPrinterDetectedErrorState. */
    fun describeStatus(status: Long?, errorBits: ByteArray?): Pair<String, String> {
        val b0 = errorBits?.getOrNull(0)?.toInt()?.and(0xff) ?: 0
        val b1 = errorBits?.getOrNull(1)?.toInt()?.and(0xff) ?: 0
        return when {
            b0 and 0x40 != 0 || b1 and 0x04 != 0 -> "error" to "Out of paper. Load paper in the rear tray."
            b0 and 0x04 != 0 -> "error" to "Paper jam. Check the printer."
            b0 and 0x08 != 0 -> "error" to "A cover is open."
            b0 and 0x10 != 0 -> "error" to "Out of ink."
            b0 and 0x02 != 0 -> "error" to "The printer is offline."
            b0 and 0x01 != 0 -> "error" to "The printer needs attention. Check its lights."
            b0 and 0x20 != 0 -> "warning" to "Ink is running low."
            status == 4L -> "busy" to "Printing…"
            status == 5L -> "busy" to "Warming up…"
            else -> "ready" to "Ready"
        }
    }

    // ---------------------------------------------------------------- paper
    /** Page geometry from canong3000.ppd: sheet size and imageable area (llx, lly, urx, ury) in points. */
    data class Media(val ivecName: String, val w: Float, val h: Float, val llx: Float, val lly: Float, val urx: Float, val ury: Float) {
        val widthPx get() = Math.round((urx - llx) * DPI / 72f)
        val heightPx get() = Math.round((ury - lly) * DPI / 72f)
        /** Top-left of the imageable area, in top-left page coordinates (points). */
        val originX get() = llx
        val originY get() = h - ury
    }

    fun media(p: Paper): Media = when (p) {
        Paper.LETTER -> Media("na_letter_8.5x11in", 612f, 792f, 18.14f, 14.18f, 594.14f, 783.50f)
        Paper.LEGAL -> Media("na_legal_8.5x14in", 612f, 1008f, 18.14f, 14.18f, 594.14f, 999.50f)
        Paper.A5 -> Media("iso_a5_148x210mm", 420f, 595f, 9.64f, 13.98f, 409.84f, 586.50f)
        Paper.A4 -> Media("iso_a4_210x297mm", 595f, 842f, 9.64f, 14.38f, 585.64f, 833.50f)
        Paper.B5 -> Media("jis_b5_182x257mm", 516f, 729f, 9.64f, 14.78f, 506.20f, 720.50f)
        Paper.PHOTO_4X6 -> Media("na_index-4x6_4x6in", 288f, 432f, 9.64f, 14.18f, 278.32f, 423.50f)
        Paper.PHOTO_5X7 -> Media("na_5x7_5x7in", 360f, 504f, 9.64f, 14.18f, 350.32f, 495.50f)
        Paper.PHOTO_8X10 -> Media("custom_canon_203x254mm", 576f, 720f, 9.64f, 14.18f, 566.32f, 711.50f)
        Paper.ENV_10 -> Media("na_number-10_4.125x9.5in", 297f, 684f, 9.64f, 82.32f, 287.32f, 661.32f)
        Paper.ENV_DL -> Media("iso_dl_110x220mm", 312f, 624f, 9.64f, 82.68f, 302.08f, 601.32f)
    }

    // ---------------------------------------------------------------- IVEC
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
        "<cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">" +
        "<ivec:contents><ivec:operation>"
    private const val HEAD_VCN = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
        "<cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\"" +
        " xmlns:vcn=\"http://www.canon.com/ns/cmd/2008/07/canon/\">" +
        "<ivec:contents><ivec:operation>"
    private const val TAIL = "</ivec:param_set></ivec:contents></cmd>"
    private const val PARAMS = "<ivec:param_set servicetype=\"print\">"

    fun escape(s: String) = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            c.code < 0x20 && c != '\t' -> {} // illegal in XML 1.0
            else -> append(c)
        }
    }

    private fun el(ns: String, tag: String, value: String?) =
        if (value.isNullOrEmpty()) "<$ns:$tag/>" else "<$ns:$tag>${escape(value)}</$ns:$tag>"

    data class JobInfo(
        val jobId: Int,
        val title: String,
        val user: String,
        val paper: Paper,
        val paperType: PaperType,
        val monochrome: Boolean,
        val uuid: String = UUID.randomUUID().toString(),
        val datetime: Date = Date(),
    ) {
        val id8 get() = "%08d".format(jobId.coerceAtLeast(0) % 100_000_000)
    }

    fun startJob(j: JobInfo) = HEAD_VCN + "StartJob</ivec:operation>" + PARAMS +
        "<ivec:jobID>${j.id8}</ivec:jobID><ivec:bidi>0</ivec:bidi>" +
        el("vcn", "forcepmdetection", "OFF") + el("ivec", "jobname", j.title) + el("ivec", "username", j.user) +
        el("ivec", "computername", null) +
        "<ivec:job_description><![CDATA[${j.uuid.replace("]]>", "]]]]><![CDATA[>")}]]></ivec:job_description>" +
        el("vcn", "host_environment", "linux") + TAIL

    fun setJobConfiguration(j: JobInfo) = HEAD + "SetJobConfiguration</ivec:operation>" + PARAMS +
        "<ivec:jobID>${j.id8}</ivec:jobID><ivec:mismatch_mode>none</ivec:mismatch_mode>" +
        "<ivec:datetime>${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(j.datetime)}</ivec:datetime>" + TAIL

    fun setConfiguration(j: JobInfo) = HEAD + "SetConfiguration</ivec:operation>" + PARAMS +
        "<ivec:jobID>${j.id8}</ivec:jobID>" +
        "<ivec:papersize>${media(j.paper).ivecName}</ivec:papersize>" +
        "<ivec:papertype>${j.paperType.canonCode}</ivec:papertype>" +
        "<ivec:borderlessprint>OFF</ivec:borderlessprint>" +
        "<ivec:printcolormode>${if (j.monochrome) "monochrome" else "color"}</ivec:printcolormode>" +
        "<ivec:duplexprint>OFF</ivec:duplexprint>" + TAIL

    fun pageConfiguration(j: JobInfo, morePages: Boolean) = HEAD_VCN + "VendorCmd</ivec:operation>" + PARAMS +
        "<ivec:jobID>${j.id8}</ivec:jobID><vcn:ijoperation>SetPageConfiguration</vcn:ijoperation>" +
        "<vcn:nextpage>${if (morePages) "ON" else "OFF"}</vcn:nextpage>" + TAIL

    fun sendData(j: JobInfo, size: Long) = HEAD + "SendData</ivec:operation>" + PARAMS +
        "<ivec:jobID>${j.id8}</ivec:jobID><ivec:format>PWGRaster</ivec:format>" +
        "<ivec:datasize>$size</ivec:datasize>" + TAIL

    fun endJob(j: JobInfo) = HEAD + "EndJob</ivec:operation>" + PARAMS + "<ivec:jobID>${j.id8}</ivec:jobID>" + TAIL

    /**
     * Write the complete byte stream for a job. [pages] are files, each holding one
     * complete PWG Raster stream (its own "RaS2"), as Canon sends one per page.
     */
    fun writeJob(out: OutputStream, j: JobInfo, pages: List<File>) {
        require(pages.isNotEmpty())
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w(startJob(j)); w(setJobConfiguration(j)); w(setConfiguration(j))
        pages.forEachIndexed { i, f ->
            w(pageConfiguration(j, i < pages.size - 1))
            w(sendData(j, f.length()))
            f.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
        w(endJob(j))
        out.flush()
    }
}
