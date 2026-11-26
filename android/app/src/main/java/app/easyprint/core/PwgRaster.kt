package app.easyprint.core

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * PWG Raster (PWG 5102.4) writer: the image format that Mopria / IPP Everywhere
 * printers (including the Wi-Fi Canon G3000 series) accept directly.
 */
class PwgRasterWriter(private val out: OutputStream) {
    init {
        out.write("RaS2".toByteArray(Charsets.US_ASCII))
    }

    data class PageHeader(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val gray: Boolean,
        val pageSizePt: Pair<Int, Int>,
        val pageSizeName: String,
        val mediaType: String,
        val printQuality: Int,
        val totalPages: Int,
        val duplex: Boolean = false,
        val tumble: Boolean = false,
    )

    private var header: PageHeader? = null
    private var bpp = 3
    private var prevLine: ByteArray? = null
    private var repeat = 0
    private var rowsWritten = 0
    private lateinit var lineBuf: ByteArray

    fun beginPage(h: PageHeader) {
        header = h
        bpp = if (h.gray) 1 else 3
        lineBuf = ByteArray(h.widthPx * bpp)
        prevLine = null
        repeat = 0
        rowsWritten = 0
        out.write(encodeHeader(h))
    }

    /** Add rows of ARGB pixels (as from Bitmap.getPixels). */
    fun writeRows(argb: IntArray, width: Int, rows: Int) {
        val h = header ?: error("beginPage first")
        require(width == h.widthPx)
        for (r in 0 until rows) {
            if (rowsWritten >= h.heightPx) return
            val base = r * width
            if (bpp == 1) {
                for (x in 0 until width) {
                    val p = argb[base + x]
                    val rr = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val bb = p and 0xff
                    lineBuf[x] = ((rr * 299 + gg * 587 + bb * 114) / 1000).toByte()
                }
            } else {
                var o = 0
                for (x in 0 until width) {
                    val p = argb[base + x]
                    lineBuf[o++] = (p shr 16).toByte()
                    lineBuf[o++] = (p shr 8).toByte()
                    lineBuf[o++] = p.toByte()
                }
            }
            addLine(lineBuf)
            rowsWritten++
        }
    }

    fun endPage() {
        val h = header ?: return
        // pad with white rows if the caller supplied fewer
        if (rowsWritten < h.heightPx) {
            java.util.Arrays.fill(lineBuf, 0xff.toByte())
            while (rowsWritten < h.heightPx) { addLine(lineBuf); rowsWritten++ }
        }
        flushLine()
        header = null
    }

    private fun addLine(line: ByteArray) {
        val prev = prevLine
        if (prev != null && repeat < 256 && prev.contentEquals(line)) {
            repeat++
            return
        }
        flushLine()
        prevLine = line.copyOf()
        repeat = 1
    }

    private fun flushLine() {
        val line = prevLine ?: return
        out.write(repeat - 1)
        encodeLine(line, out, bpp)
        prevLine = null
        repeat = 0
    }

    companion object {
        const val HEADER_SIZE = 1796

        fun encodeHeader(h: PageHeader): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE) // big-endian by default
            fun str(offset: Int, s: String) {
                val bytes = s.toByteArray(Charsets.US_ASCII).copyOf(63)
                b.position(offset); b.put(bytes)
            }
            fun u32(offset: Int, v: Int) { b.putInt(offset, v) }
            str(0, "PwgRaster")
            str(128, h.mediaType)
            u32(272, if (h.duplex) 1 else 0)
            u32(276, h.dpi); u32(280, h.dpi)
            u32(340, 1) // NumCopies
            u32(352, h.pageSizePt.first); u32(356, h.pageSizePt.second)
            u32(368, if (h.tumble) 1 else 0)
            u32(372, h.widthPx); u32(376, h.heightPx)
            val bpp = if (h.gray) 1 else 3
            u32(384, 8) // BitsPerColor
            u32(388, 8 * bpp) // BitsPerPixel
            u32(392, h.widthPx * bpp) // BytesPerLine
            u32(396, 0) // chunky
            u32(400, if (h.gray) 18 else 19) // sGray / sRGB
            u32(420, bpp) // NumColors
            u32(452, h.totalPages) // TotalPageCount
            u32(456, 1) // CrossFeedTransform
            u32(460, 1) // FeedTransform
            // cupsInteger[] starts at 452: [7] AlternatePrimary = 480, [8] PrintQuality = 484 (PWG 5102.4)
            u32(480, 0xffffff) // AlternatePrimary
            u32(484, h.printQuality)
            str(1668, "auto") // RenderingIntent
            str(1732, h.pageSizeName)
            return b.array()
        }

        /** PackBits-style PWG line encoding. */
        fun encodeLine(line: ByteArray, out: OutputStream, bpp: Int) {
            val n = line.size / bpp
            var i = 0
            fun same(a: Int, b: Int): Boolean {
                for (k in 0 until bpp) if (line[a * bpp + k] != line[b * bpp + k]) return false
                return true
            }
            while (i < n) {
                if (i + 1 < n && same(i, i + 1)) {
                    var run = 2
                    while (i + run < n && run < 128 && same(i, i + run)) run++
                    out.write(run - 1)
                    out.write(line, i * bpp, bpp)
                    i += run
                } else {
                    var lit = 1
                    while (i + lit < n && lit < 128 && !(i + lit + 1 < n && same(i + lit, i + lit + 1))) lit++
                    if (lit == 1) {
                        out.write(0)
                    } else {
                        out.write(257 - lit)
                    }
                    out.write(line, i * bpp, lit * bpp)
                    i += lit
                }
            }
        }
    }
}
