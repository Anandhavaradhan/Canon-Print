package app.easyprint.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal IPP/2.0 client: enough to query a printer and send PWG raster jobs. */
object Ipp {
    // operations
    const val PRINT_JOB = 0x0002
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B

    // delimiter tags
    const val OPERATION = 0x01
    const val JOB = 0x02
    const val END = 0x03
    const val PRINTER = 0x04
    const val UNSUPPORTED = 0x05

    // value tags
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val RESOLUTION = 0x32
    const val RANGE = 0x33
    const val BEG_COLLECTION = 0x34
    const val END_COLLECTION = 0x37
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val CHARSET = 0x47
    const val LANGUAGE = 0x48
    const val MIME = 0x49
    const val MEMBER_NAME = 0x4A
}

/** A value in an IPP response. */
sealed class IppValue {
    data class Str(val v: String) : IppValue()
    data class Int32(val v: Int) : IppValue()
    data class Bool(val v: Boolean) : IppValue()
    data class Res(val x: Int, val y: Int, val units: Int) : IppValue()
    data class Range(val lo: Int, val hi: Int) : IppValue()
    data class Collection(val members: Map<String, List<IppValue>>) : IppValue()
    data class Raw(val tag: Int, val bytes: ByteArray) : IppValue()

    fun text(): String = when (this) {
        is Str -> v
        is Int32 -> v.toString()
        is Bool -> v.toString()
        is Res -> "${x}x$y"
        is Range -> "$lo-$hi"
        is Collection -> members.toString()
        is Raw -> "raw(${bytes.size})"
    }
}

class IppResponse(
    val status: Int,
    val requestId: Int,
    /** group tag -> list of (name -> values) groups */
    val groups: List<Pair<Int, Map<String, List<IppValue>>>>,
) {
    val ok get() = status < 0x0100
    fun group(tag: Int): Map<String, List<IppValue>> =
        groups.filter { it.first == tag }.fold(mutableMapOf()) { acc, g -> acc.putAll(g.second); acc }
    fun strings(tag: Int, name: String) = group(tag)[name].orEmpty().map { it.text() }
    fun ints(tag: Int, name: String) = group(tag)[name].orEmpty().mapNotNull { (it as? IppValue.Int32)?.v }
    fun int(tag: Int, name: String) = ints(tag, name).firstOrNull()
    fun string(tag: Int, name: String) = strings(tag, name).firstOrNull()
    /** Look in [tag] first, then any group (some printers put job attributes in odd places). */
    fun anyInt(tag: Int, name: String) = int(tag, name)
        ?: groups.firstNotNullOfOrNull { g -> g.second[name]?.firstNotNullOfOrNull { (it as? IppValue.Int32)?.v } }
}

/** Builds an IPP request body. */
class IppRequest(operation: Int, requestId: Int = 1) {
    private val buf = ByteArrayOutputStream()
    private val out = DataOutputStream(buf)

    init {
        out.writeShort(0x0200) // IPP 2.0
        out.writeShort(operation)
        out.writeInt(requestId)
    }

    fun group(tag: Int) = apply { out.writeByte(tag) }

    private fun attr(tag: Int, name: String, value: ByteArray) {
        out.writeByte(tag)
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeShort(n.size); out.write(n)
        out.writeShort(value.size); out.write(value)
    }

    fun string(tag: Int, name: String, vararg values: String) = apply {
        values.forEachIndexed { i, v -> attr(tag, if (i == 0) name else "", v.toByteArray(Charsets.UTF_8)) }
    }

    fun integer(name: String, v: Int, tag: Int = Ipp.INTEGER) = apply {
        attr(tag, name, byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    }

    fun boolean(name: String, v: Boolean) = apply { attr(Ipp.BOOLEAN, name, byteArrayOf(if (v) 1 else 0)) }

    /** media-col style collection; [members] values may be String (keyword), Int, or nested Map. */
    fun collection(name: String, members: Map<String, Any>) = apply {
        attr(Ipp.BEG_COLLECTION, name, ByteArray(0))
        writeMembers(members)
        attr(Ipp.END_COLLECTION, "", ByteArray(0))
    }

    private fun writeMembers(members: Map<String, Any>) {
        for ((k, v) in members) {
            attr(Ipp.MEMBER_NAME, "", k.toByteArray(Charsets.UTF_8))
            when (v) {
                is Int -> integer("", v)
                is String -> attr(Ipp.KEYWORD, "", v.toByteArray(Charsets.UTF_8))
                is Map<*, *> -> {
                    attr(Ipp.BEG_COLLECTION, "", ByteArray(0))
                    @Suppress("UNCHECKED_CAST")
                    writeMembers(v as Map<String, Any>)
                    attr(Ipp.END_COLLECTION, "", ByteArray(0))
                }
            }
        }
    }

    /** The standard first three operation attributes. */
    fun standard(printerUri: String) = apply {
        group(Ipp.OPERATION)
        string(Ipp.CHARSET, "attributes-charset", "utf-8")
        string(Ipp.LANGUAGE, "attributes-natural-language", "en")
        string(Ipp.URI, "printer-uri", printerUri)
    }

    fun bytes(): ByteArray {
        out.writeByte(Ipp.END)
        out.flush()
        return buf.toByteArray()
    }
}

object IppParser {
    fun parse(input: InputStream): IppResponse {
        val din = DataInputStream(input.buffered())
        din.readShort() // version
        val status = din.readUnsignedShort()
        val reqId = din.readInt()
        val groups = mutableListOf<Pair<Int, Map<String, List<IppValue>>>>()
        var current: MutableMap<String, MutableList<IppValue>>? = null
        var currentTag = 0
        var lastName = ""
        try {
            while (true) {
                val tag = din.readUnsignedByte()
                if (tag == Ipp.END) break
                if (tag < 0x10) {
                    current?.let { groups += currentTag to it }
                    current = mutableMapOf(); currentTag = tag
                    continue
                }
                val name = readStr(din)
                if (name.isNotEmpty()) lastName = name
                val value = if (tag == Ipp.BEG_COLLECTION) {
                    din.skipBytes(din.readUnsignedShort())
                    readCollection(din)
                } else readValue(tag, din)
                current?.getOrPut(lastName) { mutableListOf() }?.add(value)
            }
        } catch (_: EOFException) {
        }
        current?.let { groups += currentTag to it }
        return IppResponse(status, reqId, groups)
    }

    private fun readStr(din: DataInputStream): String {
        val len = din.readUnsignedShort()
        val b = ByteArray(len); din.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun readValue(tag: Int, din: DataInputStream): IppValue {
        val len = din.readUnsignedShort()
        val b = ByteArray(len); din.readFully(b)
        fun i(off: Int) = ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
        return when (tag) {
            Ipp.INTEGER, Ipp.ENUM -> if (len == 4) IppValue.Int32(i(0)) else IppValue.Raw(tag, b)
            Ipp.BOOLEAN -> IppValue.Bool(len > 0 && b[0].toInt() != 0)
            Ipp.RESOLUTION -> if (len == 9) IppValue.Res(i(0), i(4), b[8].toInt()) else IppValue.Raw(tag, b)
            Ipp.RANGE -> if (len == 8) IppValue.Range(i(0), i(4)) else IppValue.Raw(tag, b)
            in 0x40..0x5f -> IppValue.Str(String(b, Charsets.UTF_8))
            0x35, 0x36 -> { // with-language: lang-len, lang, text-len, text
                if (len >= 4) {
                    val ll = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                    val tl = ((b[2 + ll].toInt() and 0xff) shl 8) or (b[3 + ll].toInt() and 0xff)
                    IppValue.Str(String(b, 4 + ll, tl, Charsets.UTF_8))
                } else IppValue.Str("")
            }
            else -> IppValue.Raw(tag, b)
        }
    }

    private fun readCollection(din: DataInputStream): IppValue.Collection {
        val members = mutableMapOf<String, MutableList<IppValue>>()
        var member = ""
        while (true) {
            val tag = din.readUnsignedByte()
            din.skipBytes(din.readUnsignedShort()) // name (always empty inside collections)
            when (tag) {
                Ipp.END_COLLECTION -> { din.skipBytes(din.readUnsignedShort()); return IppValue.Collection(members) }
                Ipp.MEMBER_NAME -> member = readStr(din)
                Ipp.BEG_COLLECTION -> { din.skipBytes(din.readUnsignedShort()); members.getOrPut(member) { mutableListOf() } += readCollection(din) }
                else -> members.getOrPut(member) { mutableListOf() } += readValue(tag, din)
            }
        }
    }
}

/** What we know about a printer after Get-Printer-Attributes. */
data class PrinterInfo(
    val makeAndModel: String,
    val state: Int, // 3 idle, 4 processing, 5 stopped
    val stateReasons: List<String>,
    val stateMessage: String,
    val formats: List<String>,
    val resolutions: List<Int>,
    val rasterTypes: List<String>,
    val sheetBack: String,
    val media: List<String>,
    val mediaReady: List<String>,
    val mediaTypes: List<String>,
    val sides: List<String>,
    val colorModes: List<String>,
    val qualities: List<Int>,
    val markers: List<Marker>,
) {
    data class Marker(val name: String, val color: String, val level: Int, val type: String)

    val supportsPwg get() = "image/pwg-raster" in formats
    val supportsDuplex get() = sides.any { it.startsWith("two-sided") }
    val supportsColor get() = colorModes.isEmpty() || "color" in colorModes
}

class IppClient(
    val host: String,
    val port: Int = 631,
    val path: String = "ipp/print",
    private val timeoutMs: Int = 15000,
    /** Send documents with chunked transfer encoding (like CUPS) instead of a Content-Length. */
    private val chunked: Boolean = true,
) {
    val printerUri get() = "ipp://${hostForUri()}:$port/${path.trimStart('/')}"
    private fun hostForUri() = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    private val httpUrl get() = URL("http://${hostForUri()}:$port/${path.trimStart('/')}")

    private class HttpStatusException(val code: Int) : IOException("The printer answered HTTP $code.")

    private fun post(body: ByteArray, document: File? = null, useChunked: Boolean = chunked): IppResponse {
        val conn = httpUrl.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = if (document != null) 180_000 else timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setRequestProperty("Expect", "")
            val total = body.size.toLong() + (document?.length() ?: 0L)
            if (useChunked && document != null) conn.setChunkedStreamingMode(64 * 1024)
            else conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { os ->
                os.write(body)
                document?.inputStream()?.use { it.copyTo(os, 64 * 1024) }
            }
            val code = conn.responseCode
            if (code != 200) throw HttpStatusException(code)
            return conn.inputStream.use { IppParser.parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    fun getPrinterAttributes(): PrinterInfo {
        val req = IppRequest(Ipp.GET_PRINTER_ATTRIBUTES).standard(printerUri)
            .string(Ipp.KEYWORD, "requested-attributes",
                "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-state-message",
                "document-format-supported", "pwg-raster-document-resolution-supported",
                "pwg-raster-document-type-supported", "pwg-raster-document-sheet-back",
                "media-supported", "media-ready", "media-type-supported", "sides-supported",
                "print-color-mode-supported", "print-quality-supported",
                "marker-names", "marker-colors", "marker-levels", "marker-types")
        val r = post(req.bytes())
        if (!r.ok) throw IOException("The printer refused the request (status 0x${r.status.toString(16)}).")
        val P = Ipp.PRINTER
        val names = r.strings(P, "marker-names")
        val colors = r.strings(P, "marker-colors")
        val levels = r.ints(P, "marker-levels")
        val types = r.strings(P, "marker-types")
        val markers = names.indices.map { i ->
            PrinterInfo.Marker(names[i], colors.getOrElse(i) { "#000000" }, levels.getOrElse(i) { -1 }, types.getOrElse(i) { "" })
        }
        val res = r.group(P)["pwg-raster-document-resolution-supported"].orEmpty()
            .mapNotNull { (it as? IppValue.Res)?.let { v -> if (v.units == 3) v.x else (v.x * 2.54).toInt() } }
        return PrinterInfo(
            makeAndModel = r.string(P, "printer-make-and-model") ?: "Printer",
            state = r.int(P, "printer-state") ?: 3,
            stateReasons = r.strings(P, "printer-state-reasons"),
            stateMessage = r.string(P, "printer-state-message") ?: "",
            formats = r.strings(P, "document-format-supported"),
            resolutions = res.distinct().sorted(),
            rasterTypes = r.strings(P, "pwg-raster-document-type-supported"),
            sheetBack = r.string(P, "pwg-raster-document-sheet-back") ?: "normal",
            media = r.strings(P, "media-supported"),
            mediaReady = r.strings(P, "media-ready"),
            mediaTypes = r.strings(P, "media-type-supported"),
            sides = r.strings(P, "sides-supported"),
            colorModes = r.strings(P, "print-color-mode-supported"),
            qualities = r.ints(P, "print-quality-supported"),
            markers = markers,
        )
    }

    data class JobOptions(
        val jobName: String,
        val media: String,
        val mediaType: String?,
        val colorMode: String, // color | monochrome
        val quality: Int,
        val sides: String = "one-sided",
    )

    /** Returns the job id. */
    fun printJob(raster: File, o: JobOptions): Int {
        val req = IppRequest(Ipp.PRINT_JOB).standard(printerUri)
            .string(Ipp.NAME, "requesting-user-name", "Easy Print")
            .string(Ipp.NAME, "job-name", o.jobName.take(120))
            .string(Ipp.MIME, "document-format", "image/pwg-raster")
            .group(Ipp.JOB)
            .string(Ipp.KEYWORD, "media", o.media)
            .string(Ipp.KEYWORD, "print-color-mode", o.colorMode)
            .integer("print-quality", o.quality, Ipp.ENUM)
            .string(Ipp.KEYWORD, "sides", o.sides)
            .integer("copies", 1)
        if (o.mediaType != null) req.collection("media-col", mapOf("media-type" to o.mediaType))
        val bytes = req.bytes()
        val r = try {
            post(bytes, raster)
        } catch (e: HttpStatusException) {
            // Some printers insist on a Content-Length (411) or dislike chunking.
            if (chunked && (e.code == 411 || e.code == 400 || e.code == 413)) post(bytes, raster, useChunked = false) else throw e
        }
        if (!r.ok) throw IOException(describeStatus(r))
        return r.anyInt(Ipp.JOB, "job-id") ?: 0
    }

    /** job-state: 3 pending, 4 held, 5 processing, 6 stopped, 7 canceled, 8 aborted, 9 completed */
    fun jobState(jobId: Int): Pair<Int, List<String>> {
        val req = IppRequest(Ipp.GET_JOB_ATTRIBUTES).standard(printerUri)
            .integer("job-id", jobId)
            .string(Ipp.KEYWORD, "requested-attributes", "job-state", "job-state-reasons")
        val r = post(req.bytes())
        return (r.anyInt(Ipp.JOB, "job-state") ?: 9) to r.strings(Ipp.JOB, "job-state-reasons")
    }

    fun cancelJob(jobId: Int) {
        val req = IppRequest(Ipp.CANCEL_JOB).standard(printerUri).integer("job-id", jobId)
        post(req.bytes())
    }

    private fun describeStatus(r: IppResponse): String {
        val msg = r.string(Ipp.OPERATION, "status-message")
        return when (r.status) {
            0x0400 -> "The printer didn’t understand the job${msg?.let { " ($it)" } ?: ""}."
            0x040A -> "The printer can’t print this kind of document."
            0x0507 -> "The printer is busy. Try again in a moment."
            else -> "The printer refused the job (code 0x${r.status.toString(16)}${msg?.let { ", $it" } ?: ""})."
        }
    }
}

/** Plain-language status for printer-state-reasons. */
fun describePrinterState(info: PrinterInfo): Pair<String, String> {
    val reasons = info.stateReasons.map { it.removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report") }
    fun has(vararg k: String) = reasons.any { r -> k.any { r.startsWith(it) } }
    return when {
        has("media-empty", "media-needed") -> "error" to "Out of paper. Load paper in the rear tray."
        has("media-jam") -> "error" to "Paper jam. Check the printer."
        has("door-open", "cover-open") -> "error" to "A cover is open."
        has("marker-supply-empty", "toner-empty") -> "error" to "Out of ink."
        has("offline", "shutdown") -> "error" to "The printer is offline."
        has("marker-supply-low", "toner-low") -> "warning" to "Ink is running low."
        info.state == 5 -> "error" to (info.stateMessage.ifBlank { "The printer has stopped. Check its screen." })
        info.state == 4 -> "busy" to "Printing…"
        else -> "ready" to "Ready"
    }
}
